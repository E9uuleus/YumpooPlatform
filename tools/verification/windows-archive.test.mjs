import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { quotePowerShellLiteral } from './package-utils.mjs'
import { expandWindowsArchive } from './windows-archive.mjs'

test('Windows 服务器包解压不依赖继承的 Archive 模块，并拒绝损坏的压缩包', { skip: process.platform !== 'win32' }, (context) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "yumpoo-中文'archive-"))
  context.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const source = path.join(root, 'source')
  const archive = path.join(root, 'server.zip')
  const destination = path.join(root, 'unpacked')
  fs.mkdirSync(source)
  fs.writeFileSync(path.join(source, '内容.txt'), 'validated payload', 'utf8')
  const create = [
    "$ErrorActionPreference = 'Stop'",
    'Add-Type -AssemblyName System.IO.Compression.FileSystem',
    `[IO.Compression.ZipFile]::CreateFromDirectory(${quotePowerShellLiteral(source)}, ${quotePowerShellLiteral(archive)})`,
  ].join('\n')
  const created = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', create], { encoding: 'utf8' })
  assert.equal(created.status, 0, created.stderr)

  const modules = path.join(root, 'modules')
  const incompatible = path.join(modules, 'Microsoft.PowerShell.Archive', '99.0.0')
  fs.mkdirSync(incompatible, { recursive: true })
  fs.writeFileSync(path.join(incompatible, 'Microsoft.PowerShell.Archive.psm1'), "function Expand-Archive { throw 'incompatible archive module' }\nExport-ModuleMember -Function Expand-Archive\n")
  fs.writeFileSync(path.join(incompatible, 'Microsoft.PowerShell.Archive.psd1'), "@{ RootModule = 'Microsoft.PowerShell.Archive.psm1'; ModuleVersion = '99.0.0'; GUID = 'ad8dbcec-d166-4e30-8525-c16b86623e30' }\n")
  const env = { ...process.env, PSModulePath: `${modules};${process.env.PSModulePath ?? ''}` }
  const probe = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', 'Expand-Archive -ErrorAction Stop'], { env, encoding: 'utf8' })
  assert.notEqual(probe.status, 0)
  assert.match(probe.stderr, /incompatible archive module|Microsoft\.PowerShell\.Archive/u)

  expandWindowsArchive(archive, destination, { env, stdio: 'pipe' })
  assert.equal(fs.readFileSync(path.join(destination, '内容.txt'), 'utf8'), 'validated payload')
  const damaged = path.join(root, 'damaged.zip')
  fs.writeFileSync(damaged, 'not a zip')
  assert.throws(() => expandWindowsArchive(damaged, path.join(root, 'damaged'), { env, stdio: 'pipe' }), /无法解包/u)
})
