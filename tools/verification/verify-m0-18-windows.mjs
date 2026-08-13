import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { assertM018 } from './m0-18-utils.mjs'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const handoffRoot = path.resolve(
  process.env.YUMPOO_M018_HANDOFF_ROOT ?? path.join(repositoryRoot, 'out', 'm0-18', 'portable-handoff'),
)
const m015OutputRoot = path.resolve(
  process.env.YUMPOO_M015_OUTPUT_ROOT ??
    path.join(repositoryRoot, 'desktop', 'desktop-shell', 'out', 'm0-18-windows', 'm0-15'),
)
const environment = {
  ...process.env,
  YUMPOO_M018_HANDOFF_ROOT: handoffRoot,
  YUMPOO_M015_OUTPUT_ROOT: m015OutputRoot,
  YUMPOO_M016_PAYLOAD_ROOT: handoffRoot,
}

assertM018(process.platform === 'win32' && process.arch === 'x64', 'M0-18 Windows 门禁需要 Windows x64')
runPnpmSync(['run', 'validate:m0-18:evidence'], { cwd: repositoryRoot, env: environment })
runPnpmSync(['run', 'verify:m0-18:handoff'], { cwd: repositoryRoot, env: environment })
runPnpmSync(['run', 'verify:m0-16:windows'], { cwd: repositoryRoot, env: environment })
runSync(process.execPath, [path.join(repositoryRoot, 'tools', 'verification', 'create-m0-18-evidence-pack.mjs')], {
  cwd: repositoryRoot,
  env: environment,
})

console.log('M0-18 Windows 门禁与最终开发证据包已通过。')
