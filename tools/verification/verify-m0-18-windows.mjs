import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { assertDelivery } from './delivery-utils.mjs'
import { resolveServerSmokeReceiptPath } from './m0-18-server-smoke-receipt.mjs'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const handoffRoot = path.resolve(
  process.env.YUMPOO_HANDOFF_ROOT ?? path.join(repositoryRoot, 'out', 'handoff'),
)
const m015OutputRoot = path.resolve(
  process.env.YUMPOO_DESKTOP_OUTPUT_ROOT ??
    path.join(repositoryRoot, 'desktop', 'desktop-shell', 'out', 'release'),
)
const serverSmokeReceiptPath = resolveServerSmokeReceiptPath(
  repositoryRoot,
  process.env.YUMPOO_M018_SERVER_SMOKE_RECEIPT ??
    path.join(repositoryRoot, 'out', 'm0-18', 'server-smoke-receipt.json'),
)
const environment = {
  ...process.env,
  YUMPOO_HANDOFF_ROOT: handoffRoot,
  YUMPOO_DESKTOP_OUTPUT_ROOT: m015OutputRoot,
  YUMPOO_M016_PAYLOAD_ROOT: handoffRoot,
  YUMPOO_M018_SERVER_SMOKE_RECEIPT: serverSmokeReceiptPath,
}
const validationMode = process.env.YUMPOO_M018_VALIDATION_MODE ?? 'WINDOWS_X64_CI_STAGE'

assertDelivery(process.platform === 'win32' && process.arch === 'x64', 'M0-18 Windows 门禁需要 Windows x64')
assertDelivery(
  ['WINDOWS_X64_FULL', 'WINDOWS_X64_CI_STAGE'].includes(validationMode),
  'M0-18 Windows 验证模式无效',
)
runPnpmSync(['run', 'validate:m0-18:evidence'], { cwd: repositoryRoot, env: environment })
runPnpmSync(['run', 'verify:m0-18:handoff'], { cwd: repositoryRoot, env: environment })
fs.rmSync(serverSmokeReceiptPath, { force: true })
try {
  if (validationMode === 'WINDOWS_X64_FULL') {
    runPnpmSync(['run', 'smoke:m0-16:server'], { cwd: repositoryRoot, env: environment })
  }
  runSync(process.execPath, [path.join(repositoryRoot, 'tools/verification/smoke-desktop.mjs'), '--handoff', handoffRoot], {
    cwd: repositoryRoot, env: environment,
  })
  runPnpmSync(['run', 'verify:m0-16:windows'], { cwd: repositoryRoot, env: environment })
  runSync(process.execPath, [path.join(repositoryRoot, 'tools', 'verification', 'create-m0-18-evidence-pack.mjs')], {
    cwd: repositoryRoot,
    env: environment,
  })
} finally {
  fs.rmSync(serverSmokeReceiptPath, { force: true })
}

console.log('M0-18 Windows 门禁与最终开发证据包已通过。')
