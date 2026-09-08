import fs from 'node:fs'
import { createHash } from 'node:crypto'
import path from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { runSync } from '../verification/process-utils.mjs'
import { exactBreakingChangeException, readBreakingChangeExceptions } from './breaking-change-policy.mjs'

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
const digest = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex')
const oldSha256 = digest(baseline)
const newSha256 = digest(currentSpec)
const exceptions = readBreakingChangeExceptions(repositoryRoot)

if (oldSha256 !== newSha256) {
  const exactException = exactBreakingChangeException(exceptions, oldSha256, newSha256)
  if (exactException) {
    console.log(`OpenAPI 一次性破坏变更已按精确哈希放行：${exactException.id}`)
    process.exit(0)
  }
}

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
