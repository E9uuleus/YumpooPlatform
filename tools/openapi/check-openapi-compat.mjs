import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { runSync } from '../verification/process-utils.mjs'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const baselineArgument = process.argv.slice(2).find((argument) => argument !== '--')

if (!baselineArgument) {
  throw new Error(
    'OpenAPI 兼容性检查必须显式传入历史基线：pnpm run check:openapi-compat -- <baseline>',
  )
}

const baseline = path.resolve(repositoryRoot, baselineArgument)
if (!fs.statSync(baseline, { throwIfNoEntry: false })?.isFile()) {
  throw new Error(`OpenAPI 基线文件不存在：${baseline}`)
}

const currentSpec = path.join(
  repositoryRoot,
  'contracts',
  'openapi',
  'yumpoo-v1.yaml',
)
const oldSpec = pathToFileURL(baseline).href
const newSpec = pathToFileURL(currentSpec).href

if (process.platform === 'win32') {
  runSync(
    'cmd.exe',
    [
      '/d',
      '/s',
      '/c',
      `mvnw.cmd -f ../tools/openapi/openapi-diff-pom.xml verify -Dopenapi.oldSpec=${oldSpec} -Dopenapi.newSpec=${newSpec}`,
    ],
    { cwd: path.join(repositoryRoot, 'backend') },
  )
} else {
  runSync(
    './mvnw',
    [
      '-f',
      '../tools/openapi/openapi-diff-pom.xml',
      'verify',
      `-Dopenapi.oldSpec=${oldSpec}`,
      `-Dopenapi.newSpec=${newSpec}`,
    ],
    { cwd: path.join(repositoryRoot, 'backend') },
  )
}
