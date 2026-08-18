import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

runPnpmSync(['run', 'verify:m1-14'], { cwd: root })
runSync(process.execPath, [path.join(root, 'tools', 'verification', 'verify-m1-15-assets.mjs')], { cwd: root })

console.log('M1-15 生产环境首次身份引导本地门禁已通过')
