import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

// Shared by the desktop installer and update end-to-end checks. Both install the real NSIS package for the
// current Windows user, so they run only on CI runners or when explicitly allowed, and they move any existing
// userData and yumpoo: protocol registration aside first and restore them afterwards.
export const PRODUCT_NAME = 'Yumpoo Desktop'
export const INSTALL_DIRECTORY = 'yumpoo-desktop'
export const PROTOCOL_KEY = 'HKCU\\Software\\Classes\\yumpoo'

export function profileChangesAllowed() {
  return process.env.CI === 'true' || process.env.YUMPOO_DESKTOP_INSTALLER_SMOKE === '1'
}

export function requiredEnvironment(name) {
  const value = process.env[name]
  assert(value, `缺少环境变量 ${name}`)
  return value
}

export function userDataPath() {
  return path.join(requiredEnvironment('APPDATA'), PRODUCT_NAME)
}

/** Where the installer keeps a copy of itself and electron-updater keeps blockmaps and pending downloads. */
export function updaterCachePath() {
  return path.join(requiredEnvironment('LOCALAPPDATA'), `${INSTALL_DIRECTORY}-updater`)
}

export function powershell(script) {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { encoding: 'utf8' })
  assert.equal(result.status, 0, `PowerShell 失败：${result.stderr}`)
  return result.stdout.trim()
}

export function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8' })
  assert.equal(result.status, 0, `${command} ${args[0]} 失败：${result.stderr}`)
}

export function registryKeyExists(key) {
  return spawnSync('reg.exe', ['query', key], { stdio: 'ignore' }).status === 0
}

export function spawnAndWait(command, args, timeoutMs) {
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

export async function runInstaller(installer) {
  const code = await spawnAndWait(installer, ['/S'], 300_000)
  assert.equal(code, 0, `静默安装失败，退出码 ${code}`)
}

export function installation() {
  const json = powershell(`@(Get-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*' -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -eq '${PRODUCT_NAME}' } | Select-Object DisplayVersion, Publisher, QuietUninstallString) | ConvertTo-Json -Compress`)
  const entries = json ? [JSON.parse(json)].flat() : []
  assert(entries.length <= 1, `${PRODUCT_NAME} 存在多个卸载登记项`)
  return entries[0]
}

// electron-builder records no InstallLocation; the uninstaller always sits in the installation directory.
export function installedFrom(entry) {
  const match = String(entry.QuietUninstallString ?? '').match(/^"([^"]+)"\s*(.*)$/u)
  assert(match, 'QuietUninstallString 格式不受支持')
  const location = path.dirname(match[1])
  return { location, executable: path.join(location, 'YumpooDesktop.exe'), quietUninstall: entry.QuietUninstallString }
}

// NSIS uninstallers re-launch themselves from %TEMP%, so completion is observed instead of the process exit.
export async function uninstall({ location, quietUninstall }) {
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

/** Runs `body` against a clean profile and always restores the person's own userData and protocol key. */
export async function withIsolatedProfile(work, body) {
  const userData = userDataPath()
  const userDataBackup = path.join(work, 'userData')
  const updaterCache = updaterCachePath()
  const updaterCacheBackup = path.join(work, 'updaterCache')
  const protocolBackup = path.join(work, 'protocol.reg')
  assert.equal(installation(), undefined, `${PRODUCT_NAME} 已安装；拒绝覆盖现有安装`)
  const hadUserData = fs.existsSync(userData)
  if (hadUserData) fs.renameSync(userData, userDataBackup)
  const hadUpdaterCache = fs.existsSync(updaterCache)
  if (hadUpdaterCache) fs.renameSync(updaterCache, updaterCacheBackup)
  const hadProtocol = registryKeyExists(PROTOCOL_KEY)
  if (hadProtocol) run('reg.exe', ['export', PROTOCOL_KEY, protocolBackup, '/y'])
  if (hadProtocol) run('reg.exe', ['delete', PROTOCOL_KEY, '/f'])
  try {
    return await body()
  } finally {
    const leftover = installation()
    if (leftover) await uninstall(installedFrom(leftover)).catch(error => console.error(`清理安装失败：${error.message}`))
    fs.rmSync(userData, { recursive: true, force: true })
    if (hadUserData) fs.renameSync(userDataBackup, userData)
    fs.rmSync(updaterCache, { recursive: true, force: true })
    if (hadUpdaterCache) fs.renameSync(updaterCacheBackup, updaterCache)
    if (registryKeyExists(PROTOCOL_KEY)) run('reg.exe', ['delete', PROTOCOL_KEY, '/f'])
    if (hadProtocol) run('reg.exe', ['import', protocolBackup])
  }
}
