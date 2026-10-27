import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import {
  INSTALL_DIRECTORY, PRODUCT_NAME, PROTOCOL_KEY, installLocation, installation, installedFrom, powershell, profileChangesAllowed,
  registryKeyExists, requiredEnvironment, runInstaller, spawnAndWait, spawnAndWaitForTree, uninstall, updaterCachePath, userDataPath, withIsolatedProfile,
} from './desktop-profile.mjs'

// Installs, re-installs and uninstalls the real NSIS package, in the default per-user directory and in a custom
// one, and checks the install and uninstall boundaries.
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const desktopRoot = path.join(repositoryRoot, 'desktop', 'desktop-shell')
const outputRoot = path.resolve(process.env.YUMPOO_DESKTOP_OUTPUT_ROOT ?? path.join(repositoryRoot, 'desktop', 'desktop-shell', 'out', 'release'))
const PREFERENCE_FILES = ['timer-preferences.json', 'inbox-preferences.json', 'desktop-preferences.json']
const SESSION_FIXTURES = [['auth', 'session.bin'], ['Network', 'Cookies'], ['Local State']]

if (!profileChangesAllowed()) {
  console.warn('桌面安装冒烟已跳过：会改动当前用户配置，仅在 CI=true 或 YUMPOO_DESKTOP_INSTALLER_SMOKE=1 时运行。')
  process.exit(0)
}
assert(process.platform === 'win32' && process.arch === 'x64', '桌面安装冒烟需要 Windows x64')

const descriptor = JSON.parse(fs.readFileSync(path.join(outputRoot, 'desktop-installer.json'), 'utf8'))
const installer = path.join(outputRoot, descriptor.installer.name)
assert.equal(sha256(installer), descriptor.installer.sha256, '安装包摘要与描述文件不一致')
const expectedOrigin = JSON.parse(fs.readFileSync(path.join(desktopRoot, 'distribution', 'production.json'), 'utf8')).origin
const appData = requiredEnvironment('APPDATA')
const userData = userDataPath()
const shortcuts = [
  path.join(appData, 'Microsoft', 'Windows', 'Start Menu', 'Programs', `${PRODUCT_NAME}.lnk`),
  path.join(powershell("[Environment]::GetFolderPath('Desktop')"), `${PRODUCT_NAME}.lnk`),
]
const safeModeShortcut = path.join(appData, 'Microsoft', 'Windows', 'Start Menu', 'Programs', `${PRODUCT_NAME} (Safe Mode).lnk`)
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-installer-smoke-'))
const defaultLocation = path.join(requiredEnvironment('LOCALAPPDATA'), 'Programs', INSTALL_DIRECTORY)
// Stands in for the directory page's choice; a silent install takes /D= as is.
const customLocation = path.join(work, 'YumpooCustom')

try {
  await withIsolatedProfile(async () => {
    await runInstaller(installer)
    let installed = assertInstalled(defaultLocation)
    assert(fs.existsSync(path.join(updaterCachePath(), 'installer.exe')), '安装器未保存差分更新基准副本')
    const report = await selfCheck(installed.executable)
    assert.equal(report.version, descriptor.version, '自检报告版本与安装包不一致')
    assert.equal(report.electronVersion, descriptor.electronVersion, '自检报告 Electron 版本不一致')
    assert.equal(report.distributionOrigin, expectedOrigin, '安装版未使用生产分发配置')
    assert.equal(report.diagnosticsPage, true, '安装版诊断页未能离线渲染')

    seedUserData()
    await runInstaller(installer)
    installed = assertInstalled(defaultLocation)
    for (const relative of [...PREFERENCE_FILES.map(name => [name]), ...SESSION_FIXTURES]) {
      assert(fs.existsSync(path.join(userData, ...relative)), `同版本覆盖安装不应删除 ${relative.join('/')}`)
    }

    await withLockedShortcut(async () => {
      const command = installed.quietUninstall.match(/^"([^"]+)"\s*(.*)$/u)
      assert(command)
      await spawnAndWaitForTree(command[1], command[2], 30_000)
      assert(installation(), '快捷方式持续占用时卸载必须保留登记项以便重试')
      assert(fs.existsSync(installed.executable), '快捷方式持续占用时不应删除安装文件')
      assert(registryKeyExists(PROTOCOL_KEY), '快捷方式持续占用时不应删除协议登记')
      for (const relative of [...PREFERENCE_FILES.map(name => [name]), ...SESSION_FIXTURES]) {
        assert(fs.existsSync(path.join(userData, ...relative)), '快捷方式持续占用时不应删除用户数据')
      }
    })
    await uninstall(installed)
    assertUninstalled(installed)

    // The reinstall without /D= has to find the custom directory through InstallLocation, as updates do.
    await runInstaller(installer, { directory: customLocation })
    installed = assertInstalled(customLocation)
    await runInstaller(installer)
    installed = assertInstalled(customLocation)
    await withLockedShortcut(() => uninstall(installed), 4_000)
    assertUninstalled(installed)
  })
  console.log(`桌面安装冒烟通过：${descriptor.installer.name} 默认目录与自定义目录的安装、自检、覆盖安装与卸载边界均符合预期。`)
} finally {
  fs.rmSync(work, { recursive: true, force: true })
}

function assertInstalled(expectedLocation) {
  const entry = installation()
  assert(entry, '安装后找不到卸载登记项')
  assert.equal(entry.DisplayVersion, descriptor.version, '卸载登记项版本不正确')
  assert.equal(entry.Publisher, 'Yumpoo', '卸载登记项发布者不正确')
  assert.match(String(entry.QuietUninstallString), /\s\/S$/u, 'QuietUninstallString 必须带 /S')
  const { location, executable, quietUninstall } = installedFrom(entry)
  assert(samePath(location, expectedLocation), `安装目录不正确：${location}，应为 ${expectedLocation}`)
  assert(samePath(installLocation(entry), expectedLocation), 'InstallLocation 与安装目录不一致')
  assert(fs.statSync(executable).isFile(), '安装目录缺少 YumpooDesktop.exe')
  for (const shortcut of shortcuts) assert(fs.existsSync(shortcut), `缺少快捷方式：${shortcut}`)
  const safeMode = JSON.parse(powershell(`$s = (New-Object -ComObject WScript.Shell).CreateShortcut('${safeModeShortcut.replaceAll("'", "''")}'); @{ target = $s.TargetPath; arguments = $s.Arguments } | ConvertTo-Json -Compress`))
  assert(samePath(String(safeMode.target), executable), '安全模式快捷方式未指向安装后的 EXE')
  assert.equal(safeMode.arguments, '--safe-mode', '安全模式快捷方式参数不正确')
  const command = powershell(`(Get-ItemProperty -LiteralPath 'Registry::${PROTOCOL_KEY}\\shell\\open\\command').'(default)'`)
  const target = command.match(/^"([^"]+)" "%1"$/u)
  assert(target && samePath(target[1], executable), `yumpoo 协议未指向安装后的 EXE：${command}`)
  return { location, executable, quietUninstall }
}

function assertUninstalled(installed) {
  assert(!fs.existsSync(installed.location), '卸载后安装目录仍存在')
  for (const shortcut of [...shortcuts, safeModeShortcut]) assert(!fs.existsSync(shortcut), `卸载后快捷方式仍存在：${path.basename(shortcut)}`)
  assert(!registryKeyExists(PROTOCOL_KEY), '卸载后 yumpoo 协议键仍存在')
  assert(!fs.existsSync(updaterCachePath()), '卸载后更新缓存目录仍存在')
  for (const relative of SESSION_FIXTURES) {
    assert(!fs.existsSync(path.join(userData, ...relative)), `卸载后会话数据仍存在：${relative.join('/')}`)
  }
  assert.deepEqual(fs.readdirSync(userData).sort(), [...PREFERENCE_FILES].sort(), '卸载后 userData 只应保留偏好文件')
}

/** A real Windows reader that denies delete sharing; the release marker also lets failure cleanup release it. */
async function withLockedShortcut(body, releaseAfterMs) {
  const marker = path.join(work, `shortcut-lock-${Date.now()}`)
  const released = `${marker}-release`
  const literal = value => `'${value.replaceAll("'", "''")}'`
  const script = `$ErrorActionPreference = 'Stop'
$file = [IO.File]::Open(${literal(safeModeShortcut)}, 'Open', 'Read', 'Read')
try {
  [IO.File]::WriteAllText(${literal(marker)}, 'locked')
  $deadline = [DateTime]::UtcNow.AddSeconds(40)
  while (-not [IO.File]::Exists(${literal(released)})) {
    if ([DateTime]::UtcNow -ge $deadline) { throw 'Shortcut lock release timed out' }
    Start-Sleep -Milliseconds 50
  }
} finally { $file.Dispose() }`
  const finished = spawnAndWait('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], 45_000)
    .then(code => ({ code }), error => ({ error }))
  let timer
  try {
    const deadline = Date.now() + 10_000
    while (!fs.existsSync(marker)) {
      assert(Date.now() < deadline, '快捷方式文件锁未就绪')
      await delay(50)
    }
    if (releaseAfterMs !== undefined) timer = setTimeout(() => fs.writeFileSync(released, 'release'), releaseAfterMs)
    await body()
  } finally {
    clearTimeout(timer)
    fs.writeFileSync(released, 'release')
    const result = await finished
    if (result.error) throw result.error
    assert.equal(result.code, 0, '快捷方式文件锁进程失败')
  }
}

/** %TEMP% may be an 8.3 short path while the installer, shortcuts and protocol key record the long one. */
function samePath(left, right) {
  const canonical = value => {
    const resolved = path.resolve(value)
    try {
      return fs.realpathSync.native(resolved).toLowerCase()
    } catch {
      return resolved.toLowerCase()
    }
  }
  return canonical(left) === canonical(right)
}

async function selfCheck(executable) {
  const reportPath = path.join(work, `self-check-${Date.now()}.json`)
  const code = await spawnAndWait(executable, [`--self-check=${reportPath}`], 60_000)
  const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'))
  assert.equal(code, 0, `安装自检失败：${report.failures?.join(', ')}`)
  assert.equal(report.status, 'PASS')
  return report
}

function seedUserData() {
  fs.mkdirSync(userData, { recursive: true })
  for (const name of PREFERENCE_FILES) fs.writeFileSync(path.join(userData, name), '{}\n')
  for (const relative of SESSION_FIXTURES) {
    fs.mkdirSync(path.dirname(path.join(userData, ...relative)), { recursive: true })
    fs.writeFileSync(path.join(userData, ...relative), 'installer-smoke-fixture')
  }
}

function sha256(file) {
  return createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}
