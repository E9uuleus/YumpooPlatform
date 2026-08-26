import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const backend = path.join(root, 'backend')

runPnpmSync(['run', 'validate:event-contracts'], { cwd: root })
runPnpmSync(['run', 'doc-sync'], { cwd: root })
runPnpmSync(['run', 'verify:m2-19a:assets'], { cwd: root })
runPnpmSync(['run', 'check:openapi'], { cwd: root })
if (process.platform === 'win32') runSync('cmd.exe', ['/d', '/s', '/c', 'mvnw.cmd -q clean test'], { cwd: backend })
else runSync('./mvnw', ['-q', 'clean', 'test'], { cwd: backend })
runPnpmSync(['--filter', '@yumpoo/api-client', 'build'], { cwd: root })
runPnpmSync(['--filter', '@yumpoo/web-app', 'typecheck'], { cwd: root })
runPnpmSync(['--filter', '@yumpoo/web-app', 'test'], { cwd: root })
runPnpmSync(['--filter', '@yumpoo/web-app', 'build'], { cwd: root })
console.log('M2-19A 项目表格路由、截止日期与标签目录门禁已通过。')
