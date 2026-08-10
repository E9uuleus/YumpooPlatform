import fs from 'node:fs'
import path from 'node:path'
import { isDeepStrictEqual } from 'node:util'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'
import { parse as parseYaml } from 'yaml'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const specificationPath = path.join(
  repositoryRoot,
  'contracts',
  'openapi',
  'yumpoo-v1.yaml',
)
const examplesRoot = path.join(repositoryRoot, 'contracts', 'examples')
const javaSourceRoot = path.join(
  repositoryRoot,
  'backend',
  'src',
  'main',
  'java',
  'com',
  'yumpoo',
  'platform',
  'foundation',
)
const schemaRootId = 'https://contracts.yumpoo.local/openapi-components.json'

function fail(message) {
  throw new Error(`OpenAPI golden 校验失败：${message}`)
}

function assert(condition, message) {
  if (!condition) {
    fail(message)
  }
}

function listJsonFiles(root) {
  const result = []
  function visit(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const absolute = path.join(directory, entry.name)
      if (entry.isDirectory()) {
        visit(absolute)
      } else if (entry.isFile() && entry.name.endsWith('.json')) {
        result.push(path.resolve(absolute))
      }
    }
  }
  visit(root)
  return result.sort()
}

function collectExampleReferences(value, result = new Set()) {
  if (Array.isArray(value)) {
    for (const item of value) {
      collectExampleReferences(item, result)
    }
    return result
  }
  if (value === null || typeof value !== 'object') {
    return result
  }
  for (const [key, child] of Object.entries(value)) {
    if (
      key === '$ref' &&
      typeof child === 'string' &&
      child.startsWith('#/components/examples/')
    ) {
      result.add(child.slice('#/components/examples/'.length))
    } else {
      collectExampleReferences(child, result)
    }
  }
  return result
}

function parseJavaIntegerConstants(source) {
  return Object.fromEntries(
    [...source.matchAll(/public static final int ([A-Z_]+) = (\d+);/g)].map(
      (match) => [match[1], Number(match[2])],
    ),
  )
}

function structuralSchemaConstraints(schema, additionalIgnoredKeys = new Set()) {
  if (Array.isArray(schema)) {
    return schema.map((value) =>
      structuralSchemaConstraints(value, additionalIgnoredKeys),
    )
  }
  if (schema === null || typeof schema !== 'object') {
    return schema
  }
  return Object.fromEntries(
    Object.entries(schema)
      .filter(
        ([key]) =>
          key !== 'default' &&
          key !== 'description' &&
          !additionalIgnoredKeys.has(key),
      )
      .map(([key, value]) => [
        key,
        structuralSchemaConstraints(value, additionalIgnoredKeys),
      ]),
  )
}

function schemaForExample(relativePath) {
  if (relativePath.startsWith('errors/')) {
    return 'ErrorResponse'
  }
  if (relativePath === 'pagination/empty-page.json') {
    return 'PageResponse'
  }
  if (relativePath === 'pagination/empty-cursor-page.json') {
    return 'CursorPageResponse'
  }
  fail(`${relativePath} 没有明确的 OpenAPI schema 关联`)
}

const specificationSource = fs.readFileSync(specificationPath, 'utf8')
const specification = parseYaml(specificationSource)
const schemas = specification?.components?.schemas
const parameters = specification?.components?.parameters
const examples = specification?.components?.examples
assert(schemas && parameters && examples, 'components.schemas/parameters/examples 必须存在')

const errorCodes = schemas.ErrorCode?.enum
assert(Array.isArray(errorCodes) && errorCodes.length > 0, 'ErrorCode.enum 必须非空')
assert(new Set(errorCodes).size === errorCodes.length, 'ErrorCode.enum 不得重复')
const javaErrorSource = fs.readFileSync(
  path.join(javaSourceRoot, 'application', 'error', 'StandardErrorCode.java'),
  'utf8',
)
const javaErrorCodes = [
  ...javaErrorSource.matchAll(/^    ([A-Z][A-Z0-9_]*)\(/gm),
].map((match) => match[1])
assert(
  [...errorCodes].sort().join('\n') === javaErrorCodes.sort().join('\n'),
  'OpenAPI ErrorCode 与后端 StandardErrorCode 不一致',
)

const pageMetadataProperties = schemas.PageMetadata?.properties ?? {}
const pageResponseMetadataProperties = Object.fromEntries(
  Object.entries(schemas.PageResponse?.properties ?? {}).filter(
    ([property]) => property !== 'items',
  ),
)
assert(
  Object.keys(pageMetadataProperties).sort().join('\n') ===
    Object.keys(pageResponseMetadataProperties).sort().join('\n'),
  'PageMetadata 与 PageResponse 的 metadata 字段集合不一致',
)
for (const property of Object.keys(pageMetadataProperties)) {
  assert(
    isDeepStrictEqual(
      pageMetadataProperties[property],
      pageResponseMetadataProperties[property],
    ),
    `PageMetadata.${property} 与 PageResponse.${property} 不一致`,
  )
}
assert(
  [...(schemas.PageMetadata?.required ?? [])].sort().join('\n') ===
    [...(schemas.PageResponse?.required ?? [])]
      .filter((property) => property !== 'items')
      .sort()
      .join('\n'),
  'PageMetadata 与 PageResponse 的 metadata required 集合不一致',
)
assert(
  isDeepStrictEqual(
    structuralSchemaConstraints(parameters.Page?.schema),
    structuralSchemaConstraints(pageMetadataProperties.page),
  ),
  'OpenAPI Page 参数与 PageMetadata.page 的结构约束不一致',
)
assert(
  isDeepStrictEqual(
    structuralSchemaConstraints(parameters.Size?.schema),
    structuralSchemaConstraints(pageMetadataProperties.size),
  ),
  'OpenAPI Size 参数与 PageMetadata.size 的结构约束不一致',
)
const cursorMetadataProperties = schemas.CursorPageMetadata?.properties ?? {}
const cursorResponseMetadataProperties = Object.fromEntries(
  Object.entries(schemas.CursorPageResponse?.properties ?? {}).filter(
    ([property]) => property !== 'items',
  ),
)
assert(
  isDeepStrictEqual(cursorMetadataProperties, cursorResponseMetadataProperties),
  'CursorPageMetadata 与 CursorPageResponse 的 metadata 字段约束不一致',
)
assert(
  [...(schemas.CursorPageMetadata?.required ?? [])].sort().join('\n') ===
    [...(schemas.CursorPageResponse?.required ?? [])]
      .filter((property) => property !== 'items')
      .sort()
      .join('\n'),
  'CursorPageMetadata 与 CursorPageResponse 的 metadata required 集合不一致',
)
assert(
  isDeepStrictEqual(
    structuralSchemaConstraints(
      parameters.Cursor?.schema,
      new Set(['nullable']),
    ),
    structuralSchemaConstraints(
      cursorMetadataProperties.nextCursor,
      new Set(['nullable']),
    ),
  ),
  'OpenAPI Cursor 参数与 CursorPageMetadata.nextCursor 的结构约束不一致',
)

const paginationSource = fs.readFileSync(
  path.join(javaSourceRoot, 'api', 'pagination', 'OffsetPageRequest.java'),
  'utf8',
)
const paginationConstants = parseJavaIntegerConstants(paginationSource)
assert(
  parameters.Page?.schema?.minimum === paginationConstants.MIN_PAGE &&
    parameters.Page?.schema?.default === paginationConstants.DEFAULT_PAGE &&
    parameters.Size?.schema?.minimum === paginationConstants.MIN_SIZE &&
    parameters.Size?.schema?.default === paginationConstants.DEFAULT_SIZE &&
    parameters.Size?.schema?.maximum === paginationConstants.MAX_SIZE,
  'OpenAPI Page/Size 参数与 OffsetPageRequest 常量不一致',
)

const ajv = new Ajv({
  allErrors: true,
  strict: false,
  formats: {
    int32: true,
    int64: true,
  },
})
addFormats(ajv)
ajv.addSchema(
  {
    $id: schemaRootId,
    components: { schemas },
  },
  schemaRootId,
)
const validators = new Map()
function validatorFor(schemaName) {
  if (!validators.has(schemaName)) {
    validators.set(
      schemaName,
      ajv.compile({ $ref: `${schemaRootId}#/components/schemas/${schemaName}` }),
    )
  }
  return validators.get(schemaName)
}

const responseExampleReferences = collectExampleReferences(
  specification.components.responses,
)
const referencedFiles = new Set()
let validatedErrors = 0
let validatedPagination = 0

for (const [componentName, example] of Object.entries(examples)) {
  const externalValue = example?.externalValue
  assert(
    typeof externalValue === 'string' && externalValue.endsWith('.json'),
    `${componentName} 必须使用本地 JSON externalValue`,
  )
  const absolute = path.resolve(path.dirname(specificationPath), externalValue)
  assert(
    absolute.startsWith(`${examplesRoot}${path.sep}`),
    `${componentName} 的 externalValue 逃出 contracts/examples`,
  )
  assert(fs.statSync(absolute, { throwIfNoEntry: false })?.isFile(), `${externalValue} 不存在`)
  assert(!referencedFiles.has(absolute), `${externalValue} 被重复引用`)
  referencedFiles.add(absolute)

  const relative = path.relative(examplesRoot, absolute).split(path.sep).join('/')
  const schemaName = schemaForExample(relative)
  if (schemaName === 'ErrorResponse') {
    assert(
      responseExampleReferences.has(componentName),
      `${componentName} 未接入错误 response`,
    )
    validatedErrors += 1
  } else {
    validatedPagination += 1
  }

  let value
  try {
    value = JSON.parse(fs.readFileSync(absolute, 'utf8'))
  } catch (error) {
    fail(`${relative} 不是有效 JSON：${error instanceof Error ? error.message : error}`)
  }
  const validate = validatorFor(schemaName)
  if (!validate(value)) {
    fail(`${relative} 不符合 ${schemaName}：${ajv.errorsText(validate.errors)}`)
  }
}

const allJsonFiles = listJsonFiles(examplesRoot)
assert(
  [...referencedFiles].sort().join('\n') === allJsonFiles.join('\n'),
  'contracts/examples 下每个 JSON 必须且只能被一个 externalValue 引用',
)

console.log(
  `已按 OpenAPI schema 校验 ${validatedErrors} 个错误 golden 和 ${validatedPagination} 个分页 golden。`,
)
