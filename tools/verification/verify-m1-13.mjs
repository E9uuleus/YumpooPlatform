import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const environment = {
  ...process.env,
  YUMPOO_M113_STARTED_AT: new Date().toISOString(),
}

runPnpmSync(['run', 'validate:m1-13:evidence'], {
  cwd: repositoryRoot,
  env: environment,
})
runPnpmSync(['run', 'verify:m0-18:portable'], {
  cwd: repositoryRoot,
  env: environment,
})
runPnpmSync(['run', 'verify:m1-13:http'], {
  cwd: repositoryRoot,
  env: environment,
})
runPnpmSync(['run', 'validate:m1-13:evidence', '--', '--require-report'], {
  cwd: repositoryRoot,
  env: environment,
})

console.log('M1-13 identity foundation acceptance gate passed')
