import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runSync } from './process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
if (process.env.YUMPOO_M219_LIVE_ENABLED !== 'true') {
  throw new Error('M2-19 Windows live 门禁需要显式设置 YUMPOO_M219_LIVE_ENABLED=true')
}
runSync(process.execPath, [path.join(root, 'tools', 'verification', 'verify-m0-14-live.mjs')], { cwd: root })
console.log('M2-19 Defender、NTFS、重解析点与真实文件维护 live 门禁已通过。')
