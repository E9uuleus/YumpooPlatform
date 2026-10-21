import assert from 'node:assert/strict'
import { createPrivateKey } from 'node:crypto'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { generateSigningKey, signInstaller } from '../release/desktop-update-signature.mjs'
import { installation, installedFrom, profileChangesAllowed, runInstaller, spawnAndWait, uninstall, userDataPath, withIsolatedProfile } from './desktop-profile.mjs'
import { gitHead } from './delivery-utils.mjs'
import { runPnpmSync } from './process-utils.mjs'

// In-app update end to end: two throwaway builds (N, N+1) trusting a key generated for this run, a local feed,
// an installed N that first rejects an authorization from an unknown key and then installs the authorized N+1.
// Nothing here reaches release assets; the production distribution is rebuilt afterwards.
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const desktopRoot = path.join(repositoryRoot, 'desktop', 'desktop-shell')
const CURRENT = '1.0.0'
const NEXT = '1.0.1'
const installerName = version => `YumpooDesktop-Setup-${version}-x64.exe`

if (!profileChangesAllowed()) {
  console.warn('桌面更新端到端验证已跳过：会改动当前用户配置，仅在 CI=true 或 YUMPOO_DESKTOP_INSTALLER_SMOKE=1 时运行。')
  process.exit(0)
}
assert(process.platform === 'win32' && process.arch === 'x64', '桌面更新端到端验证需要 Windows x64')

const work = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-update-e2e-'))
const outputRoot = path.join(desktopRoot, 'out', `.update-e2e-${process.pid}-${Date.now()}`)
const feed = path.join(work, 'feed')
fs.mkdirSync(feed)
const stats = { installerFull: 0, installerRanges: 0, signatureFetches: 0 }
const server = await serveFeed(feed, stats)
const feedUrl = `http://127.0.0.1:${server.address().port}/feed/`

try {
  const trusted = generateSigningKey()
  const untrusted = generateSigningKey()
  const distributionFile = path.join(work, 'distribution.json')
  const production = JSON.parse(fs.readFileSync(path.join(desktopRoot, 'distribution', 'production.json'), 'utf8'))
  fs.writeFileSync(distributionFile, JSON.stringify({
    schemaVersion: 1,
    origin: production.origin,
    update: { feed: feedUrl, channel: 'stable', publicKeys: [{ keyId: trusted.keyId, publicKey: trusted.publicKey }] },
  }))
  const testEnvironment = { ...process.env, YUMPOO_DISTRIBUTION_FILE: distributionFile }
  runPnpmSync(['--filter', '@yumpoo/desktop-shell', 'run', 'build'], { cwd: repositoryRoot, env: testEnvironment })
  for (const version of [CURRENT, NEXT]) {
    runPnpmSync(['--filter', '@yumpoo/desktop-shell', 'exec', 'electron-builder', '--config', 'electron-builder.config.cjs', '--win', 'nsis', '--x64', '--publish', 'never'], {
      cwd: repositoryRoot,
      env: { ...testEnvironment, YUMPOO_DESKTOP_TEST_VERSION: version, YUMPOO_DESKTOP_OUTPUT_ROOT: path.join(outputRoot, version) },
    })
  }
  for (const file of [installerName(NEXT), `${installerName(NEXT)}.blockmap`, 'latest.yml']) {
    fs.copyFileSync(path.join(outputRoot, NEXT, file), path.join(feed, file))
  }
  fs.copyFileSync(path.join(outputRoot, CURRENT, `${installerName(CURRENT)}.blockmap`), path.join(feed, `${installerName(CURRENT)}.blockmap`))
  const commit = gitHead(repositoryRoot)
  const sign = privatePem => {
    fs.rmSync(path.join(feed, `${installerName(NEXT)}.sig.json`), { force: true })
    signInstaller({ installer: path.join(feed, installerName(NEXT)), channel: 'stable', version: NEXT, tag: `desktop-v${NEXT}`, commit, privateKey: createPrivateKey(privatePem) })
  }

  await withIsolatedProfile(async () => {
    await runInstaller(path.join(outputRoot, CURRENT, installerName(CURRENT)))
    const entry = installation()
    assert.equal(entry?.DisplayVersion, CURRENT, '初始版本未安装')
    const { executable } = installedFrom(entry)

    sign(untrusted.privatePem)
    const rejected = await checkNow(executable, 'rejected')
    assert.equal(rejected.code, 1, '未授权的更新不应开始安装')
    assert.equal(rejected.report.phase, 'error')
    assert.equal(rejected.report.error, 'UPDATE_REJECTED_UNKNOWN_KEY')
    assert.equal(rejected.report.installStarted, false)
    await delay(5_000)
    assert.equal(installation()?.DisplayVersion, CURRENT, '被拒绝的更新改变了已安装版本')

    sign(trusted.privatePem)
    const accepted = await checkNow(executable, 'accepted')
    assert.equal(accepted.code, 0, `授权更新未能开始安装：${JSON.stringify(accepted.report)}`)
    assert.deepEqual([accepted.report.phase, accepted.report.version, accepted.report.installStarted], ['ready', NEXT, true])
    const deadline = Date.now() + 180_000
    while (installation()?.DisplayVersion !== NEXT) {
      assert(Date.now() < deadline, '静默更新安装未在 180 秒内完成')
      await delay(1_000)
    }
    await waitForUnlocked(executable)
    const selfCheckReport = path.join(work, 'self-check.json')
    assert.equal(await spawnAndWait(executable, [`--self-check=${selfCheckReport}`], 60_000), 0, '更新后自检失败')
    assert.equal(JSON.parse(fs.readFileSync(selfCheckReport, 'utf8')).version, NEXT, '更新后版本不正确')
    await uninstall(installedFrom(installation()))
  })
  assert(stats.signatureFetches >= 2, '客户端没有读取授权签名')
  // Both attempts must be differential: the second one also proves a rejection leaves no stale blockmap behind.
  assert(stats.installerFull === 0 && stats.installerRanges >= 2,
    `更新没有走差分下载（分段请求 ${stats.installerRanges} 次、完整请求 ${stats.installerFull} 次）`)
  console.log(`桌面更新端到端验证通过：${CURRENT} → ${NEXT}（差分下载，安装包分段请求 ${stats.installerRanges} 次）；未授权签名被拒绝且未安装。`)
} finally {
  server.close()
  fs.rmSync(outputRoot, { recursive: true, force: true })
  fs.rmSync(work, { recursive: true, force: true })
  const { YUMPOO_DISTRIBUTION_FILE: _testDistribution, ...productionEnvironment } = process.env
  runPnpmSync(['--filter', '@yumpoo/desktop-shell', 'run', 'build'], { cwd: repositoryRoot, env: productionEnvironment })
}

async function checkNow(executable, label) {
  const reportPath = path.join(work, `update-${label}.json`)
  const code = await spawnAndWait(executable, [`--update-check-now=${reportPath}`], 240_000)
  // The updater's own log says whether it downloaded differentially or fell back, and why.
  const log = path.join(userDataPath(), 'logs', 'desktop.log')
  const lines = fs.existsSync(log) ? fs.readFileSync(log, 'utf8').split('\n') : []
  for (const line of lines.filter(entry => /block ?map|differential|fallback|full download|update-(verified|rejected)/iu.test(entry))) {
    console.log(`[${label}] ${line}`)
  }
  fs.rmSync(log, { force: true })
  return { code, report: JSON.parse(fs.readFileSync(reportPath, 'utf8')) }
}

// The installer replaces the executable after the old process exits; wait until it can be opened for writing.
async function waitForUnlocked(file) {
  const deadline = Date.now() + 60_000
  for (;;) {
    try {
      fs.closeSync(fs.openSync(file, 'r+'))
      return
    } catch (error) {
      assert(Date.now() < deadline, `安装后的 EXE 仍被占用：${error.message}`)
      await delay(1_000)
    }
  }
}

/** Static feed with single- and multi-range support, the way differential downloads request installer blocks. */
function serveFeed(root, counters) {
  const server = http.createServer((request, response) => {
    const name = decodeURIComponent(new URL(request.url, 'http://feed').pathname.replace(/^\/feed\//u, ''))
    const file = path.join(root, path.basename(name))
    if (!name || name !== path.basename(name) || !fs.existsSync(file)) {
      response.writeHead(404).end()
      return
    }
    if (name.endsWith('.sig.json')) counters.signatureFetches += 1
    const data = fs.readFileSync(file)
    const ranges = parseRanges(request.headers.range, data.byteLength)
    if (name.endsWith('.exe')) ranges ? counters.installerRanges += 1 : counters.installerFull += 1
    if (!ranges) {
      response.writeHead(200, { 'content-length': data.byteLength, 'accept-ranges': 'bytes' }).end(data)
    } else if (ranges.length === 1) {
      const [start, end] = ranges[0]
      response.writeHead(206, { 'content-range': `bytes ${start}-${end}/${data.byteLength}`, 'content-length': end - start + 1 })
        .end(data.subarray(start, end + 1))
    } else {
      const boundary = 'yumpoo-feed-boundary'
      const parts = ranges.flatMap(([start, end]) => [
        Buffer.from(`--${boundary}\r\nContent-Type: application/octet-stream\r\nContent-Range: bytes ${start}-${end}/${data.byteLength}\r\n\r\n`),
        data.subarray(start, end + 1),
        Buffer.from('\r\n'),
      ])
      const body = Buffer.concat([...parts, Buffer.from(`--${boundary}--\r\n`)])
      response.writeHead(206, { 'content-type': `multipart/byteranges; boundary=${boundary}`, 'content-length': body.byteLength }).end(body)
    }
  })
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server)))
}

function parseRanges(header, size) {
  const match = typeof header === 'string' && header.match(/^bytes=(.+)$/u)
  if (!match) return undefined
  return match[1].split(',').map(part => {
    const [startText, endText] = part.trim().split('-')
    const start = Number(startText)
    const end = endText === '' ? size - 1 : Math.min(Number(endText), size - 1)
    assert(Number.isSafeInteger(start) && Number.isSafeInteger(end) && start <= end, `无效 Range：${header}`)
    return [start, end]
  })
}
