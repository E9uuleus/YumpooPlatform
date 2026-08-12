import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'
import { assertM016 } from './m0-16-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
assertM016(process.platform === 'win32' && process.arch === 'x64', 'verify:m0-16 需要 Windows x64')
const java = spawnSync('java', ['-version'], { encoding: 'utf8' })
const javaVersion = `${java.stdout ?? ''}\n${java.stderr ?? ''}`
assertM016(java.status === 0 && /version\s+"21(?:\.|"?\s)/u.test(javaVersion), 'verify:m0-16 需要 Java 21')
for (const [command, args, label] of [
  ['docker', ['info', '--format', '{{.ServerVersion}}'], 'Docker'],
  ['powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', '$PSVersionTable.PSVersion.ToString()'], 'PowerShell'],
]) {
  const result = spawnSync(command, args, { cwd: repositoryRoot, stdio: 'ignore' })
  assertM016(result.status === 0, `${label} 不可用`)
}
runSync(process.execPath, [path.join(repositoryRoot, 'tools', 'verification', 'verify-m0-16-assets.mjs')], { cwd: repositoryRoot })
runPnpmSync(['run', 'verify:m0-15'], { cwd: repositoryRoot })
runPnpmSync(['run', 'package:m0-16:win'], { cwd: repositoryRoot })
runSync(process.execPath, [path.join(repositoryRoot, 'tools', 'verification', 'smoke-m0-16-server.mjs')], { cwd: repositoryRoot })
runSync(process.execPath, [path.join(repositoryRoot, 'tools', 'verification', 'verify-m0-16-package.mjs')], { cwd: repositoryRoot })
