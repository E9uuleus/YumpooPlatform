import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

// Checks that install the real NSIS package for the current Windows user change the user profile, so they run
// only on CI runners or when explicitly allowed, and they move the person's own userData, updater cache and
// yumpoo: protocol registration aside first and restore them afterwards.
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

/**
 * Start-Process -Wait follows the Windows process tree even after the launcher exits. It avoids reconstructing
 * ancestry from reusable PIDs. argumentLine is a native Windows command line, passed as a PowerShell literal.
 */
export function spawnAndWaitForTree(command, argumentLine, timeoutMs) {
  const literal = value => `'${value.replaceAll("'", "''")}'`
  const script = `$ErrorActionPreference = 'Stop'
$child = Start-Process -FilePath ${literal(command)} -ArgumentList ${literal(argumentLine)} -WindowStyle Hidden -PassThru -Wait
exit $child.ExitCode`
  return spawnAndWait('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], timeoutMs)
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

// NSIS uninstallers re-launch themselves from %TEMP%: the process started here exits at once while the copy
// keeps deleting files, so the uninstall is over only when that whole process tree has exited.
export async function uninstall({ location, quietUninstall }) {
  const match = quietUninstall.match(/^"([^"]+)"\s*(.*)$/u)
  assert(match, 'QuietUninstallString 格式不受支持')
  const args = match[2].split(/\s+/u).filter(Boolean)
  assert(args.includes('/S'), 'QuietUninstallString 必须是静默卸载')
  const code = await spawnAndWaitForTree(match[1], match[2], 120_000)
  assert.equal(code, 0, `静默卸载失败，退出码 ${code}`)
  const deadline = Date.now() + 120_000
  while (installation() || fs.existsSync(location)) {
    assert(Date.now() < deadline, '静默卸载未在 120 秒内完成')
    await delay(1_000)
  }
}

/** Runs `body` against a clean profile and always restores the person's own userData, updater cache and protocol key. */
export function withIsolatedProfile(body) {
  assert.equal(installation(), undefined, `${PRODUCT_NAME} 已安装；拒绝覆盖现有安装`)
  const pieces = [
    directoryPiece(userDataPath(), 'userData'),
    directoryPiece(updaterCachePath(), 'updaterCache'),
    registryPiece(PROTOCOL_KEY, 'protocol.reg'),
  ]
  return isolateProfile(pieces, body, {
    async cleanUp() {
      const leftover = installation()
      if (leftover) await uninstall(installedFrom(leftover))
    },
  })
}

/**
 * Moves every piece of the profile into a fresh backup directory, runs `body`, then removes whatever the run
 * left and moves each piece back. Backing up is inside the same try as the run, so a failure halfway through
 * still restores the pieces already moved. When cleanup fails, nothing is restored: an uninstaller that may
 * still be running could delete what was just put back. That, or a piece that cannot be restored, keeps the
 * backup directory on disk and fails with its path; it is deleted only after every piece is back.
 */
export async function isolateProfile(pieces, body, { backupRoot = os.tmpdir(), cleanUp = async () => {} } = {}) {
  const backup = fs.mkdtempSync(path.join(backupRoot, 'yumpoo-profile-backup-'))
  const held = pieces.map(piece => piece(backup))
  let failure
  try {
    for (const piece of held) piece.isolate()
    return await body()
  } catch (error) {
    failure = error
    throw error
  } finally {
    let cleanupError
    try {
      await cleanUp()
    } catch (error) {
      cleanupError = error
    }
    if (cleanupError) {
      if (failure) console.error(failure)
      throw new Error(`清理安装失败，未恢复本机配置；确认卸载程序已退出后，从 ${backup} 手动恢复：${cleanupError.message}`, { cause: cleanupError })
    }
    const errors = []
    for (const piece of [...held].reverse()) {
      try {
        piece.restore()
      } catch (error) {
        errors.push(error)
      }
    }
    if (errors.length > 0) {
      if (failure) console.error(failure)
      throw new AggregateError(errors, `恢复本机配置失败，备份保留在 ${backup}：${errors.map(error => error.message).join('；')}`)
    }
    fs.rmSync(backup, { recursive: true, force: true })
  }
}

/**
 * A directory moved into the backup by rename. Untouched (never inspected, or present but not moved) means the
 * run never started, so restore leaves the person's directory alone; otherwise it clears the run's leftovers.
 */
export function directoryPiece(target, name) {
  return backup => {
    const saved = path.join(backup, name)
    let state = 'unknown'
    return {
      isolate() {
        state = fs.existsSync(target) ? 'present' : 'absent'
        if (state === 'absent') return
        fs.renameSync(target, saved)
        state = 'moved'
      },
      restore() {
        if (state === 'unknown' || state === 'present') return
        fs.rmSync(target, { recursive: true, force: true })
        if (state === 'moved') fs.renameSync(saved, target)
      },
    }
  }
}

const windowsRegistry = {
  exists: registryKeyExists,
  export: (key, file) => run('reg.exe', ['export', key, file, '/y']),
  remove: key => run('reg.exe', ['delete', key, '/f']),
  import: file => run('reg.exe', ['import', file]),
}

/** A registry key exported into the backup and then deleted; restore deletes the run's key and imports the export. */
export function registryPiece(key, name, registry = windowsRegistry) {
  return backup => {
    const saved = path.join(backup, name)
    let state = 'unknown'
    return {
      isolate() {
        state = registry.exists(key) ? 'present' : 'absent'
        if (state === 'absent') return
        registry.export(key, saved)
        state = 'exported'
        registry.remove(key)
        state = 'removed'
      },
      restore() {
        if (state === 'unknown' || state === 'present') return
        if (registry.exists(key)) registry.remove(key)
        if (state !== 'absent') registry.import(saved)
      },
    }
  }
}
