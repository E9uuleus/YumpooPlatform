import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const verifier = path.join(repositoryRoot, 'tools', 'verification', 'verify-m0-17.mjs')

runSync(process.execPath, [verifier, '--validate-contracts'], { cwd: repositoryRoot })
const startedAt = Date.now()
runPnpmSync(['run', 'verify:m0-14'], { cwd: repositoryRoot })
runSync(process.execPath, [verifier, '--validate-generated-after', String(startedAt)], { cwd: repositoryRoot })

console.log('M0-17 portable 门禁已通过。')
