import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

// Installs, re-installs and uninstalls the real NSIS package for the current Windows user. That changes the
// user profile, so it runs only on CI runners or when explicitly allowed; any existing userData and yumpoo:
// protocol registration are moved aside first and restored afterwards.
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const desktopRoot = path.join(repositoryRoot, 'desktop', 'desktop-shell')
const outputRoot = path.resolve(process.env.YUMPOO_M015_OUTPUT_ROOT ?? '')
const PRODUCT_NAME = 'Yumpoo Desktop'
const INSTALL_DIRECTORY = 'yumpoo-desktop'
const PROTOCOL_KEY = 'HKCU\\Software\\Classes\\yumpoo'
const PREFERENCE_FILES = ['timer-preferences.json', 'inbox-preferences.json']
const SESSION_FIXTURES = [['auth', 'session.bin'], ['Network', 'Cookies'], ['Local State']]

const allowed = process.env.CI === 'true' || process.env.YUMPOO_DESKTOP_INSTALLER_SMOKE === '1'
if (!allowed) {
  console.warn('桌面安装冒烟已跳过：会改动当前用户配置，仅在 CI=true 或 YUMPOO_DESKTOP_INSTALLER_SMOKE=1 时运行。')
  process.exit(0)
}
assert(process.platform === 'win32' && process.arch === 'x64', '桌面安装冒烟需要 Windows x64')
assert(process.env.YUMPOO_M015_OUTPUT_ROOT, '缺少 YUMPOO_M015_OUTPUT_ROOT')

const descriptor = JSON.parse(fs.readFileSync(path.join(outputRoot, 'desktop-installer.json'), 'utf8'))
const installer = path.join(outputRoot, descriptor.installer.name)
assert.equal(sha256(installer), descriptor.installer.sha256, '安装包摘要与描述文件不一致')
const expectedOrigin = JSON.parse(fs.readFileSync(path.join(desktopRoot, 'distribution', 'production.json'), 'utf8')).origin
const appData = requiredEnvironment('APPDATA')
const userData = path.join(appData, PRODUCT_NAME)
const shortcuts = [
  path.join(appData, 'Microsoft', 'Windows', 'Start Menu', 'Programs', `${PRODUCT_NAME}.lnk`),
  path.join(powershell("[Environment]::GetFolderPath('Desktop')"), `${PRODUCT_NAME}.lnk`),
]
const safeModeShortcut = path.join(appData, 'Microsoft', 'Windows', 'Start Menu', 'Programs', `${PRODUCT_NAME} (Safe Mode).lnk`)
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-installer-smoke-'))
const userDataBackup = path.join(work, 'userData')
const protocolBackup = path.join(work, 'protocol.reg')

assert.equal(installation(), undefined, `${PRODUCT_NAME} 已安装；安装冒烟拒绝覆盖现有安装`)
const hadUserData = fs.existsSync(userData)
if (hadUserData) fs.renameSync(userData, userDataBackup)
const hadProtocol = registryKeyExists(PROTOCOL_KEY)
if (hadProtocol) run('reg.exe', ['export', PROTOCOL_KEY, protocolBackup, '/y'])
if (hadProtocol) run('reg.exe', ['delete', PROTOCOL_KEY, '/f'])

try {
  let installed
  await runInstaller()
  installed = assertInstalled()
  const report = await selfCheck(installed.executable)
  assert.equal(report.version, descriptor.version, '自检报告版本与安装包不一致')
  assert.equal(report.electronVersion, descriptor.electronVersion, '自检报告 Electron 版本不一致')
  assert.equal(report.distributionOrigin, expectedOrigin, '安装版未使用生产分发配置')
  assert.equal(report.diagnosticsPage, true, '安装版诊断页未能离线渲染')

  seedUserData()
  await runInstaller()
  installed = assertInstalled()
  for (const relative of [...PREFERENCE_FILES.map(name => [name]), ...SESSION_FIXTURES]) {
    assert(fs.existsSync(path.join(userData, ...relative)), `同版本覆盖安装不应删除 ${relative.join('/')}`)
  }

  await uninstall(installed)
  assert(!fs.existsSync(installed.location), '卸载后安装目录仍存在')
  for (const shortcut of [...shortcuts, safeModeShortcut]) assert(!fs.existsSync(shortcut), `卸载后快捷方式仍存在：${path.basename(shortcut)}`)
  assert(!registryKeyExists(PROTOCOL_KEY), '卸载后 yumpoo 协议键仍存在')
  for (const relative of SESSION_FIXTURES) {
    assert(!fs.existsSync(path.join(userData, ...relative)), `卸载后会话数据仍存在：${relative.join('/')}`)
  }
  assert.deepEqual(fs.readdirSync(userData).sort(), [...PREFERENCE_FILES].sort(), '卸载后 userData 只应保留偏好文件')
  console.log(`桌面安装冒烟通过：${descriptor.installer.name} 安装、自检、覆盖安装与卸载边界均符合预期。`)
} finally {
  const leftover = installation()
  if (leftover) await uninstall(installedFrom(leftover)).catch(error => console.error(`清理安装失败：${error.message}`))
  fs.rmSync(userData, { recursive: true, force: true })
  if (hadUserData) fs.renameSync(userDataBackup, userData)
  if (registryKeyExists(PROTOCOL_KEY)) run('reg.exe', ['delete', PROTOCOL_KEY, '/f'])
  if (hadProtocol) run('reg.exe', ['import', protocolBackup])
  fs.rmSync(work, { recursive: true, force: true })
}

async function runInstaller() {
  const code = await spawnAndWait(installer, ['/S'], 300_000)
  assert.equal(code, 0, `静默安装失败，退出码 ${code}`)
}

function assertInstalled() {
  const entry = installation()
  assert(entry, '安装后找不到卸载登记项')
  assert.equal(entry.DisplayVersion, descriptor.version, '卸载登记项版本不正确')
  assert.equal(entry.Publisher, 'Yumpoo', '卸载登记项发布者不正确')
  const { location, executable, quietUninstall } = installedFrom(entry)
  assert.equal(path.resolve(location).toLowerCase(),
    path.join(requiredEnvironment('LOCALAPPDATA'), 'Programs', INSTALL_DIRECTORY).toLowerCase(), '按用户安装目录不正确')
  assert(fs.statSync(executable).isFile(), '安装目录缺少 YumpooDesktop.exe')
  for (const shortcut of shortcuts) assert(fs.existsSync(shortcut), `缺少快捷方式：${shortcut}`)
  const safeMode = JSON.parse(powershell(`$s = (New-Object -ComObject WScript.Shell).CreateShortcut('${safeModeShortcut.replaceAll("'", "''")}'); @{ target = $s.TargetPath; arguments = $s.Arguments } | ConvertTo-Json -Compress`))
  assert.equal(String(safeMode.target).toLowerCase(), executable.toLowerCase(), '安全模式快捷方式未指向安装后的 EXE')
  assert.equal(safeMode.arguments, '--safe-mode', '安全模式快捷方式参数不正确')
  const command = powershell(`(Get-ItemProperty -LiteralPath 'Registry::${PROTOCOL_KEY}\\shell\\open\\command').'(default)'`)
  assert.equal(command.toLowerCase(), `"${executable}" "%1"`.toLowerCase(), 'yumpoo 协议未指向安装后的 EXE')
  return { location, executable, quietUninstall }
}

// electron-builder records no InstallLocation; the uninstaller always sits in the installation directory.
function installedFrom(entry) {
  const match = String(entry.QuietUninstallString ?? '').match(/^"([^"]+)"\s*(.*)$/u)
  assert(match, 'QuietUninstallString 格式不受支持')
  const location = path.dirname(match[1])
  return { location, executable: path.join(location, 'YumpooDesktop.exe'), quietUninstall: entry.QuietUninstallString }
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

// NSIS uninstallers re-launch themselves from %TEMP%, so completion is observed instead of the process exit.
async function uninstall({ location, quietUninstall }) {
  const match = quietUninstall.match(/^"([^"]+)"\s*(.*)$/u)
  assert(match, 'QuietUninstallString 格式不受支持')
  const args = match[2].split(/\s+/u).filter(Boolean)
  assert(args.includes('/S'), 'QuietUninstallString 必须是静默卸载')
  await spawnAndWait(match[1], args, 120_000)
  const deadline = Date.now() + 120_000
  while (installation() || fs.existsSync(location)) {
    assert(Date.now() < deadline, '静默卸载未在 120 秒内完成')
    await delay(1_000)
  }
}

function installation() {
  const json = powershell(`@(Get-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*' -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -eq '${PRODUCT_NAME}' } | Select-Object DisplayVersion, Publisher, QuietUninstallString) | ConvertTo-Json -Compress`)
  const entries = json ? [JSON.parse(json)].flat() : []
  assert(entries.length <= 1, `${PRODUCT_NAME} 存在多个卸载登记项`)
  return entries[0]
}

function registryKeyExists(key) {
  return spawnSync('reg.exe', ['query', key], { stdio: 'ignore' }).status === 0
}

function powershell(script) {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { encoding: 'utf8' })
  assert.equal(result.status, 0, `PowerShell 失败：${result.stderr}`)
  return result.stdout.trim()
}

function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8' })
  assert.equal(result.status, 0, `${command} ${args[0]} 失败：${result.stderr}`)
}

function spawnAndWait(command, args, timeoutMs) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'ignore', windowsHide: true })
    const timer = setTimeout(() => {
      child.kill()
      reject(new Error(`${path.basename(command)} 未在 ${timeoutMs}ms 内退出`))
    }, timeoutMs)
    child.once('error', error => { clearTimeout(timer); reject(error) })
    child.once('exit', code => { clearTimeout(timer); resolve(code) })
  })
}

function requiredEnvironment(name) {
  const value = process.env[name]
  assert(value, `缺少环境变量 ${name}`)
  return value
}

function sha256(file) {
  return createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}
