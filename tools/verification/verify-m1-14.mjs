import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

runPnpmSync(['run', 'verify:m0-15'], { cwd: repositoryRoot })
runPnpmSync(['run', 'verify:m1-06'], { cwd: repositoryRoot })
runSync(process.execPath, [path.join(repositoryRoot, 'tools', 'verification', 'verify-m1-14-assets.mjs')], {
  cwd: repositoryRoot,
})

console.log('M1-14 PC Chrome 与 Electron 企业微信扫码登录本地门禁已通过')
