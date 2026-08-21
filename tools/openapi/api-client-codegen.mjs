import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync } from '../verification/process-utils.mjs'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const generatedRoot = path.join(
  repositoryRoot,
  'packages',
  'api-client',
  'src',
  'generated',
)
const sourceSpec = path.join(
  repositoryRoot,
  'contracts',
  'openapi',
  'yumpoo-v1.yaml',
)
const generatorConfig = path.join(
  repositoryRoot,
  'tools',
  'openapi',
  'typescript-fetch-config.yaml',
)

function normalizeText(value) {
  return value.replace(/\r\n/g, '\n')
}

function replaceExactlyOnce(source, expected, replacement, description) {
  const first = source.indexOf(expected)
  if (first < 0 || source.indexOf(expected, first + expected.length) >= 0) {
    throw new Error(`无法唯一应用 typescript-fetch 严格类型修正：${description}`)
  }
  return source.replace(expected, replacement)
}

function applyStrictTypeScriptCompatibility(sourceRoot) {
  const runtimePath = path.join(sourceRoot, 'runtime.ts')
  let runtime = normalizeText(fs.readFileSync(runtimePath, 'utf8'))

  runtime = replaceExactlyOnce(
    runtime,
    'set config(configuration: Configuration) {',
    'set config(configuration: ConfigurationParameters) {',
    'Configuration setter 参数',
  )
  runtime = replaceExactlyOnce(
    runtime,
    "...preMiddlewares: Array<Middleware['pre']>",
    "...preMiddlewares: Array<NonNullable<Middleware['pre']>>",
    'pre middleware 可选类型',
  )
  runtime = replaceExactlyOnce(
    runtime,
    "...postMiddlewares: Array<Middleware['post']>",
    "...postMiddlewares: Array<NonNullable<Middleware['post']>>",
    'post middleware 可选类型',
  )
  runtime = replaceExactlyOnce(
    runtime,
    `        const initParams = {
            method: context.method,
            headers,
            body: context.body,
            credentials: this.configuration.credentials,
        };`,
    `        const initParams: HTTPRequestInit = {
            method: context.method,
            headers,
            body: context.body,
            ...(this.configuration.credentials === undefined
                ? {}
                : { credentials: this.configuration.credentials }),
        };`,
    'RequestInit credentials',
  )
  runtime = replaceExactlyOnce(
    runtime,
    '                        response: response ? response.clone() : undefined,',
    '                        ...(response ? { response: response.clone() } : {}),',
    'ErrorContext response',
  )

  const marker = '/* OpenAPI Generator 7.19.0 exactOptionalPropertyTypes compatibility transform. */'
  runtime = replaceExactlyOnce(
    runtime,
    '/* eslint-disable */',
    `/* eslint-disable */\n${marker}`,
    '生成后处理标记',
  )
  fs.writeFileSync(runtimePath, runtime, 'utf8')

  const errorCodePath = path.join(sourceRoot, 'models', 'ErrorCode.ts')
  let errorCode = normalizeText(fs.readFileSync(errorCodePath, 'utf8'))
  errorCode = replaceExactlyOnce(
    errorCode,
    '    return json as ErrorCode;',
    `    return instanceOfErrorCode(json)
        ? json as ErrorCode
        : ErrorCode.UnknownDefaultOpenApi;`,
    'ErrorCode 未知枚举运行时兜底',
  )
  errorCode = replaceExactlyOnce(
    errorCode,
    '/* eslint-disable */',
    '/* eslint-disable */\n/* Unknown response enums map to UnknownDefaultOpenApi. */',
    '枚举兜底标记',
  )
  fs.writeFileSync(errorCodePath, errorCode, 'utf8')

  const errorResponsePath = path.join(sourceRoot, 'models', 'ErrorResponse.ts')
  let errorResponse = normalizeText(fs.readFileSync(errorResponsePath, 'utf8'))
  if (!errorResponse.includes('    details: EmptyErrorDetails;')) {
    throw new Error('生成的 ErrorResponse.details 未引用 EmptyErrorDetails')
  }
  errorResponse = replaceExactlyOnce(
    errorResponse,
    '/* eslint-disable */',
    '/* eslint-disable */\n/* Error details use the explicit EmptyErrorDetails contract. */',
    'ErrorResponse details 契约标记',
  )
  fs.writeFileSync(errorResponsePath, errorResponse, 'utf8')

  const emptyDetailsPath = path.join(sourceRoot, 'models', 'EmptyErrorDetails.ts')
  let emptyDetails = normalizeText(fs.readFileSync(emptyDetailsPath, 'utf8'))
  emptyDetails = replaceExactlyOnce(
    emptyDetails,
    "        'blockers': json['blockers'] == null ? undefined : ((json['blockers'] as Array<any>).map(SafeBlockerFromJSON)),",
    "        ...(json['blockers'] == null ? {} : { 'blockers': ((json['blockers'] as Array<any>).map(SafeBlockerFromJSON)) }),",
    'EmptyErrorDetails 可选 blocker 精确属性兼容',
  )
  fs.writeFileSync(emptyDetailsPath, emptyDetails, 'utf8')

  for (const relative of listGeneratedSources(sourceRoot)) {
    const generatedPath = path.join(sourceRoot, ...relative.split('/'))
    const generated = normalizeText(fs.readFileSync(generatedPath, 'utf8'))
      .replace('/* eslint-disable */\n', '')
      .replace(/[ \t]+$/gm, '')
      .replace(/\n+$/, '\n')
    fs.writeFileSync(generatedPath, generated, 'utf8')
  }
}

function listGeneratedSources(root) {
  const files = []

  function visit(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const absolute = path.join(directory, entry.name)
      if (entry.isDirectory()) {
        visit(absolute)
      } else if (entry.isFile() && entry.name.endsWith('.ts')) {
        files.push(path.relative(root, absolute).split(path.sep).join('/'))
      }
    }
  }

  visit(root)
  return files.sort()
}

function assertGeneratedTarget() {
  const expected = path.resolve(
    repositoryRoot,
    'packages',
    'api-client',
    'src',
    'generated',
  )
  if (path.resolve(generatedRoot) !== expected) {
    throw new Error('拒绝操作未解析到 packages/api-client/src/generated 的目录')
  }
}

function generateInto(outputRoot) {
  const relativeSpec = path.relative(repositoryRoot, sourceSpec)
  const relativeOutput = path.relative(repositoryRoot, outputRoot)
  const relativeConfig = path.relative(repositoryRoot, generatorConfig)
  runPnpmSync(
    [
      'exec',
      'openapi-generator-cli',
      'generate',
      '--input-spec',
      relativeSpec,
      '--generator-name',
      'typescript-fetch',
      '--output',
      relativeOutput,
      '--config',
      relativeConfig,
    ],
    { cwd: repositoryRoot },
  )
}

function materialize(sourceRoot) {
  assertGeneratedTarget()
  fs.rmSync(generatedRoot, { recursive: true, force: true })

  for (const relative of listGeneratedSources(sourceRoot)) {
    const source = path.join(sourceRoot, ...relative.split('/'))
    const destination = path.join(generatedRoot, ...relative.split('/'))
    fs.mkdirSync(path.dirname(destination), { recursive: true })
    fs.writeFileSync(
      destination,
      normalizeText(fs.readFileSync(source, 'utf8')),
      'utf8',
    )
  }
}

function verifyDrift(sourceRoot) {
  if (!fs.existsSync(generatedRoot)) {
    throw new Error('生成 SDK 不存在；请运行 pnpm run generate:api-client')
  }

  const expectedFiles = listGeneratedSources(sourceRoot)
  const actualFiles = listGeneratedSources(generatedRoot)
  const mismatches = []

  if (expectedFiles.join('\n') !== actualFiles.join('\n')) {
    mismatches.push('文件清单不同')
  }

  for (const relative of expectedFiles) {
    const actual = path.join(generatedRoot, ...relative.split('/'))
    if (!fs.existsSync(actual)) {
      continue
    }
    const expectedText = normalizeText(
      fs.readFileSync(path.join(sourceRoot, ...relative.split('/')), 'utf8'),
    )
    const actualText = normalizeText(fs.readFileSync(actual, 'utf8'))
    if (expectedText !== actualText) {
      mismatches.push(relative)
    }
  }

  if (mismatches.length > 0) {
    throw new Error(
      `生成 SDK 与 OpenAPI 不一致（${mismatches.join(', ')}）；请运行 pnpm run generate:api-client`,
    )
  }
}

const mode = process.argv[2]
if (!['generate', 'check'].includes(mode)) {
  throw new Error('用法：node tools/openapi/api-client-codegen.mjs <generate|check>')
}

const temporaryParent = path.join(repositoryRoot, 'node_modules', '.cache')
fs.mkdirSync(temporaryParent, { recursive: true })
const temporaryRoot = fs.mkdtempSync(
  path.join(temporaryParent, 'yumpoo-api-client-'),
)
try {
  generateInto(temporaryRoot)
  const generatedSourceRoot = path.join(temporaryRoot, 'src')
  applyStrictTypeScriptCompatibility(generatedSourceRoot)
  if (mode === 'generate') {
    materialize(generatedSourceRoot)
    console.log('typescript-fetch SDK 已从 OpenAPI 重新生成。')
  } else {
    verifyDrift(generatedSourceRoot)
    console.log('typescript-fetch SDK 与 OpenAPI 一致。')
  }
} finally {
  const resolvedTemporary = path.resolve(temporaryRoot)
  const expectedPrefix = path.resolve(temporaryParent, 'yumpoo-api-client-')
  if (resolvedTemporary.startsWith(expectedPrefix)) {
    fs.rmSync(resolvedTemporary, { recursive: true, force: true })
  }
}
