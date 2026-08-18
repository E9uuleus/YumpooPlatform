import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

runPnpmSync(['run', 'verify:m1-15'], { cwd: root })
runSync(process.execPath, [path.join(root, 'tools', 'verification', 'verify-m1-15-deployment-assets.mjs')], { cwd: root })
runSync(process.execPath, [path.join(root, 'tools', 'verification', 'verify-m1-15-powershell.mjs')], { cwd: root })
runPnpmSync(['run', 'build'], { cwd: root })

const backend = path.join(root, 'backend')
if (process.platform === 'win32') {
  runSync(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', 'mvnw.cmd -q -DskipTests package'], { cwd: backend })
} else {
  runSync('./mvnw', ['-q', '-DskipTests', 'package'], { cwd: backend })
}
runPnpmSync(['run', 'package:m1-15:win'], { cwd: root })
runPnpmSync(['run', 'verify:m1-15:package'], { cwd: root })

console.log('M1-15 本地验收和 Windows 服务器包门禁已通过；真实首次引导证据仍为 ENV_PENDING')
