import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { pnpmInvocation, stopProcessTree } from './process-utils.mjs'
import { validateM113Evidence } from './m1-13-evidence.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const jarPath = path.join(repositoryRoot, 'backend', 'target', 'yumpoo-server.jar')
const webDist = path.join(repositoryRoot, 'frontend', 'web-app', 'dist', 'index.html')
const outputRoot = path.join(repositoryRoot, 'out', 'm1-13')
const reportPath = path.join(outputRoot, 'verification-report.json')
const backendPort = 8100
const previewPort = 18174
const containerName = `yumpoo-m113-${process.pid}-${Date.now()}`
const databasePassword = 'M113-Database-Only-2026!'
const controlledCorpId = 'corp-m113-controlled'
const controlledMemberId = 'member-m113-controlled'
const backupMemberId = 'member-m113-backup'
const sessionKey = 'MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE='
const startedAt = new Date().toISOString()
const previewBaseUrl = `http://127.0.0.1:${previewPort}`
let application
let preview
let containerStarted = false

class CookieJar {
  #values = new Map()

  absorb(setCookies) {
    for (const raw of setCookies) {
      const first = raw.split(';', 1)[0]
      const separator = first.indexOf('=')
      if (separator < 1) continue
      const name = first.slice(0, separator)
      const value = first.slice(separator + 1)
      if (!value || /(?:^|;)\s*Max-Age=0(?:;|$)/iu.test(raw)) {
        this.#values.delete(name)
      } else {
        this.#values.set(name, value)
      }
    }
  }

  has(name) {
    return this.#values.has(name)
  }

  required(name) {
    const value = this.#values.get(name)
    assert(value, `required security cookie is missing: ${name}`)
    return value
  }

  header() {
    return [...this.#values.entries()].map(([name, value]) => `${name}=${value}`).join('; ')
  }
}

fs.mkdirSync(outputRoot, { recursive: true })
fs.rmSync(reportPath, { force: true })
assert(fs.existsSync(jarPath), 'packaged backend JAR is missing')
assert(fs.existsSync(webDist), 'production Web build is missing')
await assertPortAvailable(backendPort)
await assertPortAvailable(previewPort)

try {
  runDocker([
    'run', '--detach', '--rm', '--name', containerName,
    '-e', 'POSTGRES_DB=yumpoo',
    '-e', 'POSTGRES_USER=m113',
    '-e', `POSTGRES_PASSWORD=${databasePassword}`,
    '-p', '127.0.0.1::5432',
    'postgres:17.10-alpine',
  ])
  containerStarted = true
  await waitUntil(
    () => runDockerQuiet(['exec', containerName, 'pg_isready', '-U', 'm113', '-d', 'yumpoo']),
    60_000,
    'fresh PostgreSQL did not become ready',
  )
  const databasePort = postgresPort()

  application = startApplication(databasePort)
  await waitUntil(
    () => healthMatches(`http://127.0.0.1:${backendPort}/actuator/health/readiness`),
    90_000,
    'packaged backend did not become ready',
  )

  const databaseState = verifyDatabaseState()
  preview = startPreview()
  await waitUntil(() => previewReady(), 60_000, 'Vite production preview did not become ready')

  const root = await request('/')
  assert(root.response.status === 200 && root.text.includes('id="app"'), 'preview root did not serve the production SPA')
  const routed = await request('/admin/members')
  assert(routed.response.status === 200 && routed.text.includes('id="app"'), 'preview did not preserve the SPA management route')

  const firstSession = new CookieJar()
  await login(firstSession, true)
  const me = await request('/api/v1/auth/me', { jar: firstSession })
  assert(me.response.status === 200, `controlled /auth/me failed with ${me.response.status}`)
  const current = parseJson(me.text, '/auth/me')
  assert(
    JSON.stringify(current.roles) === JSON.stringify(['COMPANY_MEMBER', 'COMPANY_ADMIN']),
    'controlled member role snapshot is incorrect',
  )
  const company = await request('/api/v1/company', { jar: firstSession })
  assert(company.response.status === 200, 'COMPANY_ADMIN could not read company context')
  const members = await request('/api/v1/admin/members', { jar: firstSession })
  assert(members.response.status === 200, 'COMPANY_ADMIN could not read identity administration')
  const self = await request(`/api/v1/admin/members/${current.user.id}`, { jar: firstSession })
  assert(self.response.status === 200, 'controlled member detail is unavailable')
  const selfEtag = self.response.headers.get('etag')
  assert(selfEtag, 'controlled member detail omitted ETag')

  const retiredCookies = firstSession.header()
  const logout = await request('/api/v1/auth/logout', {
    method: 'POST',
    jar: firstSession,
    headers: { 'X-XSRF-TOKEN': firstSession.required('__Host-yumpoo-csrf') },
  })
  assert(logout.response.status === 204, 'logout did not return 204')
  const loggedOut = await request('/api/v1/auth/me', { cookieHeader: retiredCookies })
  assert(loggedOut.response.status === 401, 'logged-out session remained usable')

  const roleBoundarySession = new CookieJar()
  await login(roleBoundarySession, true)
  const roleMe = parseJson((await request('/api/v1/auth/me', { jar: roleBoundarySession })).text, 'role-boundary /auth/me')
  const roleSelf = await request(`/api/v1/admin/members/${roleMe.user.id}`, { jar: roleBoundarySession })
  const roleSelfEtag = roleSelf.response.headers.get('etag')
  assert(roleSelf.response.status === 200 && roleSelfEtag, 'role-boundary session could not read its member ETag')
  const deniedRole = await request('/api/v1/admin/app-manager-assignments', {
    method: 'POST',
    jar: roleBoundarySession,
    headers: writeHeaders(roleBoundarySession, roleSelfEtag),
    body: JSON.stringify({
      userId: roleMe.user.id,
      reason: 'M1-13 role boundary verification',
    }),
  })
  assert(deniedRole.response.status === 403 && deniedRole.text.includes('ACCESS_DENIED'), 'COMPANY_ADMIN crossed the APP_MANAGER role boundary')

  const secondSession = new CookieJar()
  await login(secondSession, true)
  const secondMe = parseJson((await request('/api/v1/auth/me', { jar: secondSession })).text, 'second /auth/me')
  const secondSelf = await request(`/api/v1/admin/members/${secondMe.user.id}`, { jar: secondSession })
  const secondEtag = secondSelf.response.headers.get('etag')
  assert(secondSelf.response.status === 200 && secondEtag, 'second session could not read its member ETag')
  const disabledCookies = secondSession.header()
  const disabled = await request(`/api/v1/admin/members/${secondMe.user.id}/account-disable`, {
    method: 'POST',
    jar: secondSession,
    headers: writeHeaders(secondSession, secondEtag),
    body: JSON.stringify({ reason: 'M1-13 session invalidation verification' }),
  })
  assert(disabled.response.status === 200, `self-disable failed with ${disabled.response.status}: ${disabled.text}`)
  const invalidated = await request('/api/v1/auth/me', { cookieHeader: disabledCookies })
  assert(invalidated.response.status === 403 && invalidated.text.includes('ACCOUNT_DISABLED'), 'disabled account session did not fail on the next request')

  const rejectedSession = new CookieJar()
  await login(rejectedSession, false)
  assert(!rejectedSession.has('__Host-yumpoo-session'), 'disabled login created a new session cookie')

  assertNoSecretLeak()
  const { liveEvidence } = validateM113Evidence(repositoryRoot)
  const report = {
    schemaVersion: 1,
    milestone: 'M1-13',
    status: 'PASS',
    gitSha: gitSha(),
    startedAt,
    completedAt: new Date().toISOString(),
    mode: 'EXTERNAL_HTTP',
    provider: 'CONTROLLED',
    springProfiles: ['local', 'm1-13-e2e'],
    flywayVersion: databaseState.flywayVersion,
    liveEvidence,
    checks: {
      portsAvailable: true,
      freshPostgres: true,
      packagedJarReady: true,
      cleanMigrations: true,
      noProjectFacts: true,
      fixtureViaServices: true,
      spaPreview: true,
      controlledLogin: true,
      roleBoundary: true,
      logoutInvalidation: true,
      accountDisableInvalidation: true,
      disabledReloginRejected: true,
      secretsRedacted: true,
    },
  }
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
  console.log('M1-13 fresh database, packaged JAR, controlled identity and production SPA HTTP gate passed')
} catch (error) {
  printDiagnostics('backend', application?.output)
  printDiagnostics('preview', preview?.output)
  throw error
} finally {
  stopOwnedProcess(preview)
  stopOwnedProcess(application)
  if (containerStarted) {
    spawnSync('docker', ['rm', '--force', containerName], { stdio: 'ignore' })
  }
}

function startApplication(databasePort) {
  const jdbcUrl = `jdbc:postgresql://127.0.0.1:${databasePort}/yumpoo`
  return capturedSpawn('java', ['-jar', jarPath], {
    cwd: outputRoot,
    env: {
      ...process.env,
      SPRING_PROFILES_ACTIVE: 'local,m1-13-e2e',
      SPRING_DATASOURCE_URL: jdbcUrl,
      SPRING_DATASOURCE_USERNAME: 'm113',
      SPRING_DATASOURCE_PASSWORD: databasePassword,
      YUMPOO_SERVER_PORT: String(backendPort),
      YUMPOO_CONTROLLED_AUTH_ENABLED: 'true',
      YUMPOO_CONTROLLED_AUTH_CORP_ID: controlledCorpId,
      YUMPOO_CONTROLLED_AUTH_MEMBER_ID: controlledMemberId,
      YUMPOO_M113_FIXTURE_ENABLED: 'true',
      YUMPOO_M113_BACKUP_MEMBER_ID: backupMemberId,
      YUMPOO_SESSION_CURRENT_KEY_VERSION: 'm113-local-v1',
      YUMPOO_SESSION_CURRENT_KEY: sessionKey,
      YUMPOO_OUTBOX_ENABLED: 'false',
    },
  })
}

function startPreview() {
  const invocation = pnpmInvocation(['--filter', '@yumpoo/web-app', 'preview'])
  return capturedSpawn(invocation.command, invocation.args, {
    cwd: repositoryRoot,
    env: process.env,
  })
}

function capturedSpawn(command, args, options) {
  const child = spawn(command, args, {
    ...options,
    detached: process.platform !== 'win32',
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  child.output = ''
  const capture = (chunk) => {
    child.output = `${child.output}${chunk.toString('utf8')}`.slice(-512 * 1024)
  }
  child.stdout.on('data', capture)
  child.stderr.on('data', capture)
  return child
}

async function login(jar, expectSuccess) {
  const authorize = await request('/api/v1/auth/wecom/authorize', { jar })
  assert(authorize.response.status === 302, `controlled authorize failed with ${authorize.response.status}`)
  assertSecurityCookies(authorize.setCookies, ['__Host-yumpoo-oauth-nonce'])
  const callback = authorize.response.headers.get('location')
  assert(callback?.startsWith('/api/v1/auth/wecom/callback?'), 'controlled authorize returned an unsafe callback')
  const completed = await request(callback, { jar })
  if (!expectSuccess) {
    assert(
      completed.response.status === 302
        && completed.response.headers.get('location') === '/login?reason=authentication',
      'disabled controlled login did not return the sanitized login redirect',
    )
    return
  }
  assert(completed.response.status === 302 && completed.response.headers.get('location') === '/', 'controlled callback did not complete with a same-origin redirect')
  assertSecurityCookies(completed.setCookies, ['__Host-yumpoo-session', '__Host-yumpoo-csrf'])
  jar.required('__Host-yumpoo-session')
  jar.required('__Host-yumpoo-csrf')
}

function writeHeaders(jar, etag) {
  return {
    'Content-Type': 'application/json',
    'Idempotency-Key': randomUUID(),
    'If-Match': etag,
    'X-XSRF-TOKEN': jar.required('__Host-yumpoo-csrf'),
  }
}

async function request(pathOrUrl, {
  method = 'GET',
  jar,
  cookieHeader,
  headers = {},
  body,
} = {}) {
  const finalHeaders = { ...headers }
  const cookies = cookieHeader ?? jar?.header()
  if (cookies) finalHeaders.Cookie = cookies
  const response = await fetch(new URL(pathOrUrl, previewBaseUrl), {
    method,
    headers: finalHeaders,
    body,
    redirect: 'manual',
    signal: AbortSignal.timeout(10_000),
  })
  const setCookies = response.headers.getSetCookie()
  jar?.absorb(setCookies)
  return { response, text: await response.text(), setCookies }
}

function assertSecurityCookies(setCookies, requiredNames) {
  for (const name of requiredNames) {
    const cookie = setCookies.find((value) => value.startsWith(`${name}=`))
    assert(cookie, `missing Set-Cookie for ${name}`)
    assert(cookie.includes('Secure') && cookie.includes('SameSite=Lax'), `${name} omitted security attributes`)
    if (name !== '__Host-yumpoo-csrf') {
      assert(cookie.includes('HttpOnly'), `${name} must be HttpOnly`)
    }
  }
}

function verifyDatabaseState() {
  const flywayVersion = psql("SELECT max(version::integer) FROM yumpoo.flyway_schema_history WHERE success = true")
  const missingM113Migrations = Number(psql("SELECT count(*) FROM generate_series(1, 14) AS required(version) WHERE NOT EXISTS (SELECT 1 FROM yumpoo.flyway_schema_history AS history WHERE history.success = true AND history.version::integer = required.version)"))
  assert(missingM113Migrations === 0, `required M1-13 migrations are missing: ${missingM113Migrations}`)
  assert(/^\d+$/u.test(flywayVersion) && Number(flywayVersion) >= 14, `unexpected Flyway version: ${flywayVersion}`)
  const forbiddenTables = Number(psql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'yumpoo' AND lower(table_name) ~ '(project|product|membership|owner)' AND table_name NOT IN ('project_template_definition', 'project_template_content_blueprint')"))
  assert(forbiddenTables === 0, 'clean M1 database contains project/product/membership/owner instance facts')
  const counts = psql("SELECT (SELECT count(*) FROM yumpoo.identity_user) || '|' || (SELECT count(*) FROM yumpoo.external_identity) || '|' || (SELECT count(*) FROM yumpoo.platform_role_assignment)")
  assert(counts === '2|2|2', `M1-13 service fixture counts are incorrect: ${counts}`)
  const roles = psql("SELECT string_agg(role_code || ':' || status, ',' ORDER BY role_code) FROM yumpoo.platform_role_assignment")
  assert(roles === 'APP_MANAGER:ACTIVE,COMPANY_ADMIN:ACTIVE', `M1-13 fixture roles are incorrect: ${roles}`)
  return { flywayVersion }
}

function psql(sql) {
  return runDocker(['exec', containerName, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'm113', '-d', 'yumpoo', '-At', '-c', sql]).trim()
}

function postgresPort() {
  const output = runDocker(['port', containerName, '5432/tcp'])
  const match = output.match(/127\.0\.0\.1:(\d+)/u)
  assert(match, 'unable to resolve the random PostgreSQL host port')
  return Number(match[1])
}

function runDocker(args) {
  const result = spawnSync('docker', args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 })
  if (result.status !== 0) {
    printDiagnostics('docker', `${result.stdout ?? ''}\n${result.stderr ?? ''}`)
    throw new Error('M1-13 verification failed: Docker command failed')
  }
  return result.stdout.trim()
}

function runDockerQuiet(args) {
  return spawnSync('docker', args, { stdio: 'ignore' }).status === 0
}

async function previewReady() {
  try {
    const response = await fetch(`${previewBaseUrl}/`, { signal: AbortSignal.timeout(2000) })
    return response.status === 200
  } catch {
    return false
  }
}

async function healthMatches(url) {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(2000) })
    const body = await response.json()
    return response.status === 200 && body.status === 'UP'
  } catch {
    return false
  }
}

async function waitUntil(probe, timeoutMilliseconds, failureMessage) {
  const deadline = Date.now() + timeoutMilliseconds
  while (Date.now() < deadline) {
    if (await probe()) return
    await new Promise((resolve) => setTimeout(resolve, 300))
  }
  throw new Error(`M1-13 verification failed: ${failureMessage}`)
}

function assertPortAvailable(port) {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.unref()
    server.once('error', () => reject(new Error(`M1-13 verification failed: port ${port} is already in use`)))
    server.listen(port, '127.0.0.1', () => server.close((error) => (error ? reject(error) : resolve())))
  })
}

function stopOwnedProcess(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null || !child.pid) return
  if (process.platform === 'win32') {
    stopProcessTree(child)
    return
  }
  try {
    process.kill(-child.pid, 'SIGTERM')
  } catch {
    child.kill('SIGTERM')
  }
}

function assertNoSecretLeak() {
  const output = `${application?.output ?? ''}\n${preview?.output ?? ''}`
  for (const secret of [databasePassword, controlledCorpId, controlledMemberId, backupMemberId, sessionKey]) {
    assert(!output.includes(secret), 'runtime output exposed M1-13 fixture credentials or identifiers')
  }
}

function printDiagnostics(label, output) {
  if (!output) return
  const scrubbed = String(output)
    .replaceAll(databasePassword, '[REDACTED]')
    .replaceAll(controlledCorpId, '[REDACTED]')
    .replaceAll(controlledMemberId, '[REDACTED]')
    .replaceAll(backupMemberId, '[REDACTED]')
    .replaceAll(sessionKey, '[REDACTED]')
    .replaceAll(repositoryRoot, '[REPOSITORY]')
  process.stderr.write(`--- M1-13 ${label} diagnostics ---\n${scrubbed.slice(-32 * 1024)}\n`)
}

function gitSha() {
  const result = spawnSync('git', ['rev-parse', 'HEAD'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
  })
  assert(result.status === 0 && /^[0-9a-f]{40}$/u.test(result.stdout.trim()), 'unable to resolve current Git SHA')
  return result.stdout.trim()
}

function parseJson(value, label) {
  try {
    return JSON.parse(value)
  } catch {
    throw new Error(`M1-13 verification failed: ${label} did not return JSON`)
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(`M1-13 verification failed: ${message}`)
}
