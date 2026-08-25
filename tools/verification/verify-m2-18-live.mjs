import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runSync } from './process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
if (process.env.YUMPOO_M218_LIVE_ENABLED !== 'true') {
  throw new Error('M2-18 live 门禁需要显式设置 YUMPOO_M218_LIVE_ENABLED=true')
}
runSync(process.execPath, [path.join(root, 'tools', 'verification', 'verify-m0-14-live.mjs')], { cwd: root })
console.log('M2-18 Defender、NTFS、同卷原子移动与 EICAR live 门禁已通过。')
