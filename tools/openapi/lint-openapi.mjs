import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync } from '../verification/process-utils.mjs'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)

process.env.REDOCLY_SUPPRESS_UPDATE_NOTICE = 'true'
process.env.REDOCLY_TELEMETRY = 'off'

runPnpmSync(['exec', 'redocly', 'lint', '--config', 'contracts/redocly.yaml'], {
  cwd: repositoryRoot,
})
