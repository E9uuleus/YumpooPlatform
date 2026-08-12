import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const outputRoot = path.join(
  repositoryRoot,
  'desktop',
  'desktop-shell',
  'out',
  `.m0-15-verify-${process.pid}-${Date.now()}`,
)

runSync(
  process.execPath,
  [
    path.join(
      repositoryRoot,
      'tools',
      'verification',
      'verify-m0-15-live.mjs',
    ),
    '--validate-evidence',
  ],
  { cwd: repositoryRoot },
)

runPnpmSync(['run', 'package:m0-15:win'], {
  cwd: repositoryRoot,
  env: {
    ...process.env,
    YUMPOO_M015_OUTPUT_ROOT: outputRoot,
  },
})
runSync(
  process.execPath,
  [
    path.join(
      repositoryRoot,
      'tools',
      'verification',
      'verify-m0-15-live.mjs',
    ),
    '--validate-manifest',
    path.join(
      outputRoot,
      'm0-15-artifact-manifest.json',
    ),
  ],
  { cwd: repositoryRoot },
)
runPnpmSync(['run', 'verify:m0-14'], { cwd: repositoryRoot })
