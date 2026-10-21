import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const outputRoot = process.env.YUMPOO_DESKTOP_OUTPUT_ROOT
  ? path.resolve(process.env.YUMPOO_DESKTOP_OUTPUT_ROOT)
  : path.join(
      repositoryRoot,
      'desktop',
      'desktop-shell',
      'out',
      `.m0-15-verify-${process.pid}-${Date.now()}`,
    )

runSync(
  process.execPath,
  [path.join(repositoryRoot, 'tools', 'verification', 'verify-desktop-package.mjs'), '--validate-evidence'],
  { cwd: repositoryRoot },
)
runPnpmSync(['run', 'package:desktop:win'], {
  cwd: repositoryRoot,
  env: { ...process.env, YUMPOO_DESKTOP_OUTPUT_ROOT: outputRoot },
})
runSync(
  process.execPath,
  [
    path.join(repositoryRoot, 'tools', 'verification', 'verify-desktop-package.mjs'),
    '--validate-manifest',
    path.join(outputRoot, 'desktop-artifact-manifest.json'),
  ],
  { cwd: repositoryRoot },
)
runSync(
  process.execPath,
  [path.join(repositoryRoot, 'tools', 'verification', 'verify-desktop-installer.mjs')],
  { cwd: repositoryRoot, env: { ...process.env, YUMPOO_DESKTOP_OUTPUT_ROOT: outputRoot } },
)
runSync(
  process.execPath,
  [path.join(repositoryRoot, 'tools', 'verification', 'verify-desktop-update.mjs')],
  { cwd: repositoryRoot },
)

console.log(`M0-15 Windows 叶子门禁已通过：${path.relative(repositoryRoot, outputRoot)}`)
