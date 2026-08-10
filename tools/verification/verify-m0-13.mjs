import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)

runSync(
  process.execPath,
  [
    path.join(
      repositoryRoot,
      'tools',
      'verification',
      'verify-m0-13-live.mjs',
    ),
    '--validate-evidence',
  ],
  { cwd: repositoryRoot },
)
runPnpmSync(['run', 'verify:m0-12'], { cwd: repositoryRoot })
