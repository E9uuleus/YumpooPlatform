import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const backendRoot = path.join(repositoryRoot, 'backend')
const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m203-'))
const baselinePath = path.join(temporaryRoot, 'openapi-baseline.yaml')
const metadataPath = path.join(temporaryRoot, 'openapi-baseline.metadata.json')
const baseReference = process.env.YUMPOO_M203_BASE_REF ?? 'origin/dev'

try {
  runPnpmSync(['run', 'validate:event-contracts'], { cwd: repositoryRoot })
  runPnpmSync(['run', 'doc-sync'], { cwd: repositoryRoot })
  runPnpmSync(['run', 'verify:m2-03:assets'], { cwd: repositoryRoot })
  runSync(process.execPath, [
    path.join(repositoryRoot, 'tools', 'openapi', 'extract-openapi-baseline.mjs'),
    baseReference, baselinePath, metadataPath,
  ], { cwd: repositoryRoot })
  runPnpmSync(['run', 'check:openapi-compat', '--', baselinePath], { cwd: repositoryRoot })

  if (process.platform === 'win32') {
    runSync('cmd.exe', ['/d', '/s', '/c', 'mvnw.cmd clean verify'], { cwd: backendRoot })
  } else {
    runSync('./mvnw', ['clean', 'verify'], { cwd: backendRoot })
  }

  runPnpmSync(['run', 'verify:node'], { cwd: repositoryRoot })
  console.log('M2-03 Product 生命周期与负责人治理完整门禁已通过。')
} finally {
  fs.rmSync(temporaryRoot, { recursive: true, force: true })
}
