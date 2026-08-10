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
      'verify-m0-12-live.mjs',
    ),
    '--validate-evidence',
  ],
  { cwd: repositoryRoot },
)
runPnpmSync(['run', 'validate:event-contracts'], { cwd: repositoryRoot })
runPnpmSync(['run', 'check:openapi'], { cwd: repositoryRoot })

if (process.platform === 'win32') {
  runSync('cmd.exe', ['/d', '/s', '/c', 'mvnw.cmd clean verify'], {
    cwd: path.join(repositoryRoot, 'backend'),
  })
} else {
  runSync('./mvnw', ['clean', 'verify'], {
    cwd: path.join(repositoryRoot, 'backend'),
  })
}

runPnpmSync(['run', 'verify:node'], { cwd: repositoryRoot })
