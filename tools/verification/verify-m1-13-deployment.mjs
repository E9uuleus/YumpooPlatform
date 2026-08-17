import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

runPnpmSync(['run', 'verify:m1-13'], { cwd: repositoryRoot })
runSync(process.execPath, [path.join(repositoryRoot, 'tools', 'verification', 'verify-m1-13-deployment-assets.mjs')], { cwd: repositoryRoot })
runPnpmSync(['run', 'package:m1-13:win'], { cwd: repositoryRoot })
runSync(process.execPath, [path.join(repositoryRoot, 'tools', 'verification', 'verify-m1-13-package.mjs')], { cwd: repositoryRoot })

console.log('M1-13 完整验收与 Windows 手工部署包门禁已通过')
