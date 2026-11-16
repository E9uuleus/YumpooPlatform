import { spawnSync } from 'node:child_process'
import { quotePowerShellLiteral } from './package-utils.mjs'

export function expandWindowsArchive(archive, destination, options = {}) {
  const command = [
    "$ErrorActionPreference = 'Stop'",
    'Add-Type -AssemblyName System.IO.Compression.FileSystem',
    `[IO.Compression.ZipFile]::ExtractToDirectory(${quotePowerShellLiteral(archive)}, ${quotePowerShellLiteral(destination)})`,
  ].join('\n')
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], {
    env: options.env ?? process.env,
    stdio: options.stdio ?? 'inherit',
  })
  if (result.status !== 0) throw new Error(`无法解包 ${archive}${result.error ? `：${result.error.message}` : ''}`)
}
