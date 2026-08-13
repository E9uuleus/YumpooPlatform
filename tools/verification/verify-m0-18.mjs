import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { assertM018 } from './m0-18-utils.mjs'
import { runPnpmSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

assertM018(process.platform === 'win32' && process.arch === 'x64', '完整 M0-18 本地复现需要 Windows x64')
runPnpmSync(['run', 'verify:m0-18:portable'], { cwd: repositoryRoot })
runPnpmSync(['run', 'verify:m0-18:windows'], { cwd: repositoryRoot })

console.log('M0-18 本地完整复现已通过。')
