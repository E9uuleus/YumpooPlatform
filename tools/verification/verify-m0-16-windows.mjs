import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { assertM016 } from './m0-16-utils.mjs'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const m015OutputRoot = process.env.YUMPOO_M015_OUTPUT_ROOT
  ? path.resolve(process.env.YUMPOO_M015_OUTPUT_ROOT)
  : path.join(repositoryRoot, 'desktop', 'desktop-shell', 'out', 'm0-18-windows', 'm0-15')

assertM016(process.platform === 'win32' && process.arch === 'x64', 'Windows 叶子门禁需要 Windows x64')
const java = spawnSync('java', ['-version'], { encoding: 'utf8' })
const javaVersion = `${java.stdout ?? ''}\n${java.stderr ?? ''}`
assertM016(java.status === 0 && /version\s+"21(?:\.|"?\s)/u.test(javaVersion), 'Windows 叶子门禁需要 Java 21')
const powershell = spawnSync(
  'powershell.exe',
  ['-NoProfile', '-NonInteractive', '-Command', '$PSVersionTable.PSVersion.ToString()'],
  { cwd: repositoryRoot, stdio: 'ignore' },
)
assertM016(powershell.status === 0, 'PowerShell 不可用')

runSync(process.execPath, [path.join(repositoryRoot, 'tools', 'verification', 'verify-m0-16-assets.mjs')], { cwd: repositoryRoot })
runPnpmSync(['run', 'verify:m0-15:windows'], {
  cwd: repositoryRoot,
  env: { ...process.env, YUMPOO_M015_OUTPUT_ROOT: m015OutputRoot },
})
runPnpmSync(['run', 'package:m0-16:win'], { cwd: repositoryRoot })
runSync(process.execPath, [path.join(repositoryRoot, 'tools', 'verification', 'verify-m0-16-package.mjs')], { cwd: repositoryRoot })

console.log('M0-16 Windows 打包、资产与 ZIP 叶子门禁已通过。')
