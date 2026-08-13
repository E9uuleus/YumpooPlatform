import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
runPnpmSync(['run', 'verify:m0-14'], { cwd: repositoryRoot })
runPnpmSync(['run', 'verify:m0-16:windows'], { cwd: repositoryRoot })
runPnpmSync(['run', 'smoke:m0-16:server'], { cwd: repositoryRoot })
