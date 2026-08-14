import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const backendRoot = path.join(repositoryRoot, 'backend')

if (process.platform === 'win32') {
  runSync('cmd.exe', ['/d', '/s', '/c', 'mvnw.cmd clean verify'], {
    cwd: backendRoot,
  })
} else {
  runSync('./mvnw', ['clean', 'verify'], { cwd: backendRoot })
}

runPnpmSync(['run', 'verify:node'], { cwd: repositoryRoot })

console.log('M1-08 平台/企业角色与授权策略验证已通过。')
