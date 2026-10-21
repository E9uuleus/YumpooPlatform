import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { pnpmInvocation, runPnpmSync, stopProcessTree } from '../verification/process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const args = process.argv.slice(2).filter(arg => arg !== '--' && arg !== '--skip-web-build')
const skipWebBuild = process.argv.includes('--skip-web-build')
const containerName = `yumpoo-e2e-${process.pid}`
const children = []
let containerStarted = false

function docker(args) {
  const result = spawnSync('docker', args, { encoding: 'utf8', windowsHide: true })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`Docker 执行失败：${result.stderr.trim()}`)
  return result.stdout.trim()
}

function freePort(port) {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', () => reject(new Error(`端口 ${port} 不可用；可用 YUMPOO_E2E_BASE_URL 指向已经运行的测试环境。`)))
    server.listen(port, '127.0.0.1', () => server.close(resolve))
  })
}

async function waitUntil(probe, timeout, label) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await probe()) return
    await delay(250)
  }
  throw new Error(`${label} 未在 ${timeout / 1000} 秒内就绪，查看 out/e2e/logs/。`)
}

async function httpReady(url, health = false) {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(2000) })
    return response.status === 200 && (!health || (await response.json()).status === 'UP')
  } catch {
    return false
  }
}

function startLogged(command, args, env, name) {
  const logs = path.join(root, 'out/e2e/logs')
  fs.mkdirSync(logs, { recursive: true })
  const output = fs.openSync(path.join(logs, `${name}.log`), 'w')
  const child = spawn(command, args, { cwd: root, env, windowsHide: true, stdio: ['ignore', output, output] })
  fs.closeSync(output)
  child.on('error', error => console.error(`${name} 启动失败：${error.message}`))
  children.push(child)
  return child
}

try {
  if (!process.env.YUMPOO_E2E_BASE_URL) {
    await Promise.all([freePort(8100), freePort(18174)])
    if (!fs.existsSync(path.join(root, 'backend/target/yumpoo-server.jar'))) {
      throw new Error('缺少 backend/target/yumpoo-server.jar；先运行 .\\backend\\mvnw.cmd -f backend/pom.xml -DskipTests package')
    }
    if (!skipWebBuild) {
      runPnpmSync(['--filter', '@yumpoo/api-client', 'build'], { cwd: root, windowsHide: true })
      runPnpmSync(['--filter', '@yumpoo/web-app', 'build'], { cwd: root, windowsHide: true })
    }
    docker(['run', '-d', '--rm', '--name', containerName, '-p', '127.0.0.1::5432',
      '-e', 'POSTGRES_DB=yumpoo_e2e', '-e', 'POSTGRES_USER=yumpoo_e2e',
      '-e', 'POSTGRES_PASSWORD=yumpoo_e2e', 'postgres:17.10-alpine'])
    containerStarted = true
    await waitUntil(() => spawnSync('docker', ['exec', containerName, 'pg_isready', '-U', 'yumpoo_e2e', '-d', 'yumpoo_e2e'],
      { stdio: 'ignore', windowsHide: true }).status === 0, 60_000, 'PostgreSQL')
    const databasePort = docker(['port', containerName, '5432/tcp']).match(/127\.0\.0\.1:(\d+)/u)[1]
    const jdbcUrl = `jdbc:postgresql://127.0.0.1:${databasePort}/yumpoo_e2e`
    const environment = Object.fromEntries(Object.entries(process.env)
      .filter(([key]) => ['PATH', 'SYSTEMROOT', 'TEMP', 'TMP', 'JAVA_HOME'].includes(key.toUpperCase())))
    startLogged('java', ['-jar', 'backend/target/yumpoo-server.jar'], {
      ...environment,
      SPRING_PROFILES_ACTIVE: 'local',
      YUMPOO_LOCAL_AUTH_ENABLED: 'true',
      SPRING_DATASOURCE_URL: jdbcUrl,
      SPRING_DATASOURCE_USERNAME: 'yumpoo_e2e',
      SPRING_DATASOURCE_PASSWORD: 'yumpoo_e2e',
      SPRING_FLYWAY_URL: jdbcUrl,
      SPRING_FLYWAY_USER: 'yumpoo_e2e',
      SPRING_FLYWAY_PASSWORD: 'yumpoo_e2e',
    }, 'backend')
    await waitUntil(() => httpReady('http://127.0.0.1:8100/actuator/health/readiness', true), 90_000, '后端')
    const preview = pnpmInvocation(['--filter', '@yumpoo/web-app', 'preview'])
    startLogged(preview.command, preview.args, process.env, 'preview')
    await waitUntil(() => httpReady('http://127.0.0.1:18174/'), 60_000, 'Web preview')
  }
  const playwright = pnpmInvocation(['exec', 'playwright', 'test', '-c', 'tools/e2e/playwright.config.ts', ...args])
  const result = spawnSync(playwright.command, playwright.args, { cwd: root, stdio: 'inherit', windowsHide: true })
  if (result.error) throw result.error
  process.exitCode = result.status ?? 1
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
} finally {
  for (const child of children.reverse()) stopProcessTree(child)
  if (containerStarted) docker(['rm', '-f', containerName])
}
