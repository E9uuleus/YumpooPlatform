import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const baselinePath = path.resolve(
  process.env.YUMPOO_M018_BASELINE_PATH ?? path.join(repositoryRoot, 'out', 'm0-18', 'openapi-baseline.yaml'),
)
const metadataPath = path.resolve(
  process.env.YUMPOO_M018_BASELINE_METADATA_PATH ??
    path.join(repositoryRoot, 'out', 'm0-18', 'openapi-baseline.metadata.json'),
)
const baseReference = process.env.YUMPOO_M018_BASE_REF ?? 'origin/dev'
const startedAt = new Date().toISOString()
const environment = {
  ...process.env,
  YUMPOO_M018_STARTED_AT: startedAt,
  YUMPOO_M018_BASELINE_PATH: baselinePath,
  YUMPOO_M018_BASELINE_METADATA_PATH: metadataPath,
}

runPnpmSync(['run', 'validate:m0-18:evidence'], { cwd: repositoryRoot, env: environment })
runPnpmSync(['run', 'test:m0-18'], { cwd: repositoryRoot, env: environment })
runSync(
  process.execPath,
  [path.join(repositoryRoot, 'tools', 'openapi', 'extract-openapi-baseline.mjs'), baseReference, baselinePath, metadataPath],
  { cwd: repositoryRoot, env: environment },
)
runPnpmSync(['run', 'check:openapi-compat', '--', baselinePath], { cwd: repositoryRoot, env: environment })
runPnpmSync(['run', 'verify:m0-17:portable'], { cwd: repositoryRoot, env: environment })
runPnpmSync(['run', 'smoke:m0-16:server'], { cwd: repositoryRoot, env: environment })
runPnpmSync(['run', 'smoke:desktop'], { cwd: repositoryRoot, env: environment })
runPnpmSync(['run', 'create:m0-18:handoff'], { cwd: repositoryRoot, env: environment })
runPnpmSync(['run', 'verify:m0-18:handoff'], { cwd: repositoryRoot, env: environment })

console.log('M0-18 portable 门禁、OpenAPI 基线与 handoff 已通过。')
