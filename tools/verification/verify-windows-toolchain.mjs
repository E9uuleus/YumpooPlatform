import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { assertPackage } from './package-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
assertPackage(process.platform === 'win32' && process.arch === 'x64', 'Windows 叶子门禁需要 Windows x64')
const java = spawnSync('java', ['-version'], { encoding: 'utf8' })
const javaVersion = `${java.stdout ?? ''}\n${java.stderr ?? ''}`
assertPackage(java.status === 0 && /version\s+"21(?:\.|"?\s)/u.test(javaVersion), 'Windows 叶子门禁需要 Java 21')
const powershell = spawnSync(
  'powershell.exe',
  ['-NoProfile', '-NonInteractive', '-Command', '$PSVersionTable.PSVersion.ToString()'],
  { cwd: repositoryRoot, stdio: 'ignore' },
)
assertPackage(powershell.status === 0, 'PowerShell 不可用')

