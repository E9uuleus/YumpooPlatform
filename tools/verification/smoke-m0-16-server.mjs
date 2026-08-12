import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { assertM016 } from './m0-16-utils.mjs'
import { stopProcessTree } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const jarPath = path.join(repositoryRoot, 'backend', 'target', 'yumpoo-server.jar')
const smokeRoot = path.join(repositoryRoot, 'out', 'm0-16', 'smoke')
const containerName = `yumpoo-m016-${process.pid}-${Date.now()}`
const applicationPassword = 'M016-SENTINEL-DO-NOT-LOG-2026!'
const migrationPassword = 'M016-Migration-Only-2026!'
let application
let containerStarted = false

assertM016(process.platform === 'win32', 'packaged JAR 冒烟仅支持 Windows')
assertM016(fs.existsSync(jarPath), 'packaged JAR 不存在')

try {
  prepareDirectories()
  runDocker([
    'run', '--detach', '--rm', '--name', containerName,
    '-e', 'POSTGRES_DB=yumpoo',
    '-e', 'POSTGRES_USER=m016_migrator',
    '-e', `POSTGRES_PASSWORD=${migrationPassword}`,
    '-p', '127.0.0.1::5432',
    'postgres:17.10-alpine',
  ])
  containerStarted = true
  await waitUntil(async () => runDockerQuiet(['exec', containerName, 'pg_isready', '-U', 'm016_migrator', '-d', 'yumpoo']), 60_000, 'PostgreSQL 未就绪')
  runDocker([
    'exec', containerName, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'm016_migrator', '-d', 'yumpoo',
    '-c', `CREATE ROLE m016_app LOGIN PASSWORD '${applicationPassword}';`,
  ], { suppressFailureOutput: true })
  const databasePort = postgresPort()
  const serverPort = await freePort()
  writeExternalConfiguration(databasePort, serverPort)

  await assertInvalidConfigurationRejected(serverPort)

  application = startJava(serverPort, [
    fileLocation(path.join(smokeRoot, 'config')),
    fileLocation(path.join(smokeRoot, 'secrets')),
  ])
  const baseUrl = `http://127.0.0.1:${serverPort}`
  await waitUntil(async () => healthMatches(`${baseUrl}/actuator/health/liveness`, 200, 'UP'), 90_000, 'packaged JAR liveness 未就绪')
  await waitUntil(async () => healthMatches(`${baseUrl}/actuator/health/readiness`, 200, 'UP'), 30_000, 'packaged JAR readiness 未就绪')
  assertM016(await statusOf(`${baseUrl}/actuator/env`) === 404, '/actuator/env 不得暴露')
  verifyLoopbackListener(application.pid, serverPort)

  const attachmentRoot = path.join(smokeRoot, 'attachment')
  const unavailableRoot = path.join(smokeRoot, 'attachment-unavailable')
  fs.renameSync(attachmentRoot, unavailableRoot)
  try {
    await waitUntil(async () => healthMatches(`${baseUrl}/actuator/health/readiness`, 503, 'DOWN'), 20_000, '附件目录故障未使 readiness DOWN')
    assertM016(await healthMatches(`${baseUrl}/actuator/health/liveness`, 200, 'UP'), '目录故障时 liveness 必须保持 UP')
  } finally {
    fs.renameSync(unavailableRoot, attachmentRoot)
  }
  await waitUntil(async () => healthMatches(`${baseUrl}/actuator/health/readiness`, 200, 'UP'), 20_000, '附件目录恢复后 readiness 未恢复')

  runDocker(['stop', '--time', '1', containerName])
  containerStarted = false
  await waitUntil(async () => healthMatches(`${baseUrl}/actuator/health/readiness`, 503, 'DOWN'), 30_000, '数据库故障未使 readiness DOWN')
  assertM016(await healthMatches(`${baseUrl}/actuator/health/liveness`, 200, 'UP'), '数据库故障时 liveness 必须保持 UP')
  console.log('M0-16 packaged JAR 回环监听、外部配置、目录/数据库健康语义和脱敏拒启已通过')
} finally {
  stopProcessTree(application)
  if (containerStarted) {
    spawnSync('docker', ['rm', '--force', containerName], { stdio: 'ignore' })
  }
}

function prepareDirectories() {
  fs.rmSync(smokeRoot, { recursive: true, force: true })
  for (const name of ['config', 'secrets', 'bad-config', 'release', 'attachment', 'upload-temp', 'logs']) {
    fs.mkdirSync(path.join(smokeRoot, name), { recursive: true })
  }
}

function writeExternalConfiguration(databasePort, serverPort) {
  const jdbc = `jdbc:postgresql://127.0.0.1:${databasePort}/yumpoo`
  const ordinary = `server:\n  address: 127.0.0.1\n  port: ${serverPort}\nspring:\n  datasource:\n    url: ${jdbc}\n    username: m016_app\n    hikari:\n      connection-timeout: 1000\n  flyway:\n    url: ${jdbc}\n    user: m016_migrator\nyumpoo:\n  outbox:\n    enabled: false\n  deployment:\n    public-base-url: https://yumpoo.example.invalid\n    release-root: ${yamlPath('release')}\n    config-root: ${yamlPath('config')}\n    secrets-root: ${yamlPath('secrets')}\n    attachment-root: ${yamlPath('attachment')}\n    upload-temp-root: ${yamlPath('upload-temp')}\n    log-root: ${yamlPath('logs')}\n`
  const secrets = `spring:\n  datasource:\n    password: ${applicationPassword}\n  flyway:\n    password: ${migrationPassword}\n`
  const invalid = 'yumpoo:\n  deployment:\n    public-base-url: http://invalid.example.test\n'
  fs.writeFileSync(path.join(smokeRoot, 'config', 'application-prod.yml'), ordinary, 'utf8')
  fs.writeFileSync(path.join(smokeRoot, 'secrets', 'application-prod.yml'), secrets, 'utf8')
  fs.writeFileSync(path.join(smokeRoot, 'bad-config', 'application-prod.yml'), invalid, 'utf8')
}

async function assertInvalidConfigurationRejected(serverPort) {
  const badPort = await freePort()
  const child = startJava(badPort, [
    fileLocation(path.join(smokeRoot, 'config')),
    fileLocation(path.join(smokeRoot, 'secrets')),
    fileLocation(path.join(smokeRoot, 'bad-config')),
  ])
  const exitCode = await waitForExit(child, 60_000)
  assertM016(exitCode !== 0, '非 HTTPS 公开地址必须拒绝启动')
  assertM016(child.output.includes('PUBLIC_ORIGIN_INVALID:yumpoo.deployment.public-base-url'), '拒启日志缺少稳定错误码与配置项')
  assertM016(!child.output.includes(applicationPassword), '拒启日志泄露哨兵 Secret')
  assertM016(!child.output.includes(migrationPassword), '拒启日志泄露迁移 Secret')
  assertM016(!child.output.includes(smokeRoot), '拒启日志泄露目录路径')
  assertM016(badPort !== serverPort, '负向启动必须使用独立端口')
}

function startJava(port, locations) {
  const child = spawn('java', ['-jar', jarPath], {
    cwd: smokeRoot,
    windowsHide: true,
    env: {
      ...process.env,
      SPRING_PROFILES_ACTIVE: 'prod',
      SPRING_CONFIG_ADDITIONAL_LOCATION: locations.join(','),
      YUMPOO_SERVER_PORT: String(port),
    },
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

function waitForExit(child, timeoutMilliseconds) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      stopProcessTree(child)
      reject(new Error('M0-16 验证失败：无效配置进程未在时限内退出'))
    }, timeoutMilliseconds)
    child.once('error', (error) => {
      clearTimeout(timer)
      reject(error)
    })
    child.once('exit', (code) => {
      clearTimeout(timer)
      resolve(code)
    })
  })
}

function verifyLoopbackListener(pid, port) {
  const command = `$m016Connections = @(Get-NetTCPConnection -State Listen -LocalPort ${port} | Select-Object LocalAddress,LocalPort,OwningProcess); ConvertTo-Json -InputObject $m016Connections -Compress`
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { encoding: 'utf8' })
  assertM016(result.status === 0, '无法读取 packaged JAR 监听地址')
  const connections = JSON.parse(result.stdout || '[]')
  assertM016(connections.length > 0, 'packaged JAR 未建立监听')
  assertM016(connections.every((item) => item.LocalAddress === '127.0.0.1' && item.LocalPort === port && item.OwningProcess === pid), 'packaged JAR 必须仅由目标 Java 进程监听 127.0.0.1')
}

async function healthMatches(url, expectedStatus, expectedHealth) {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(2000) })
    const text = await response.text()
    const parsed = JSON.parse(text)
    return response.status === expectedStatus && Object.keys(parsed).length === 1 && parsed.status === expectedHealth
  } catch {
    return false
  }
}

async function statusOf(url) {
  try {
    return (await fetch(url, { signal: AbortSignal.timeout(2000) })).status
  } catch {
    return 0
  }
}

async function waitUntil(probe, timeoutMilliseconds, failureMessage) {
  const deadline = Date.now() + timeoutMilliseconds
  while (Date.now() < deadline) {
    if (await probe()) return
    await new Promise((resolve) => setTimeout(resolve, 300))
  }
  throw new Error(`M0-16 验证失败：${failureMessage}`)
}

function runDocker(args, options = {}) {
  const result = spawnSync('docker', args, { encoding: 'utf8' })
  if (result.status !== 0) {
    if (!options.suppressFailureOutput) {
      process.stderr.write(result.stderr ?? '')
    }
    throw new Error('M0-16 验证失败：Docker 命令执行失败')
  }
  return result.stdout.trim()
}

function runDockerQuiet(args) {
  return spawnSync('docker', args, { stdio: 'ignore' }).status === 0
}

function postgresPort() {
  const output = runDocker(['port', containerName, '5432/tcp'])
  const match = output.match(/127\.0\.0\.1:(\d+)/u)
  assertM016(match, '无法读取 PostgreSQL 随机端口')
  return Number(match[1])
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.unref()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      const port = typeof address === 'object' && address ? address.port : 0
      server.close((error) => (error ? reject(error) : resolve(port)))
    })
  })
}

function fileLocation(directory) {
  return pathToFileURL(`${directory}${path.sep}`).href
}

function yamlPath(name) {
  return path.join(smokeRoot, name).replaceAll('\\', '/')
}
