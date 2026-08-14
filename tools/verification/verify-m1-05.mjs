import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const backendRoot = path.join(repositoryRoot, 'backend')

runSync(
  process.execPath,
  [path.join(repositoryRoot, 'tools', 'verification', 'verify-m1-04-live.mjs'), '--validate-evidence'],
  { cwd: repositoryRoot },
)

if (process.platform === 'win32') {
  runSync('cmd.exe', ['/d', '/s', '/c', 'mvnw.cmd clean verify'], {
    cwd: backendRoot,
  })
} else {
  runSync('./mvnw', ['clean', 'verify'], { cwd: backendRoot })
}

runPnpmSync(['run', 'verify:node'], { cwd: repositoryRoot })

console.log('M1-05 通讯录部分失败、对账、离职与返聘验证已通过。')
