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
  [
    path.join(
      repositoryRoot,
      'tools',
      'verification',
      'verify-m0-14-live.mjs',
    ),
    '--validate-evidence',
  ],
  { cwd: repositoryRoot },
)

if (process.platform === 'win32') {
  runSync(
    'cmd.exe',
    [
      '/d',
      '/s',
      '/c',
      'mvnw.cmd -q -Dtest=M014BoundedHeapVerification -DargLine=-Xmx96m test',
    ],
    { cwd: backendRoot },
  )
} else {
  runSync(
    './mvnw',
    [
      '-q',
      '-Dtest=M014BoundedHeapVerification',
      '-DargLine=-Xmx96m',
      'test',
    ],
    { cwd: backendRoot },
  )
}

runPnpmSync(['run', 'verify:m0-13'], { cwd: repositoryRoot })
