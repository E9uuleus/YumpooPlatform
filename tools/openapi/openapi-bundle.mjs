import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse, stringify } from 'yaml'

const rootKeys = ['openapi', 'info', 'servers', 'tags', 'components']
const componentKeys = ['securitySchemes', 'schemas', 'requestBodies', 'parameters', 'headers', 'examples', 'responses']
const banner = '# 由 contracts/openapi/yumpoo-v1/ 生成，勿手工编辑；运行 pnpm generate:openapi\n'

function assertKeys(value, allowed, file) {
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) throw new Error(`${file} 包含未知键：${key}`)
  }
}

function mergeMap(target, source, label) {
  for (const [key, value] of Object.entries(source)) {
    if (Object.hasOwn(target, key)) throw new Error(`${label} 包含重复键：${key}`)
    Object.defineProperty(target, key, { value, enumerable: true, writable: true, configurable: true })
  }
}

function filesUnder(directory, prefix = '') {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name
    return entry.isDirectory() ? filesUnder(path.join(directory, entry.name), relative) : [relative]
  })
}

export function bundleOpenApi(sourceRoot) {
  const read = file => parse(fs.readFileSync(path.join(sourceRoot, file), 'utf8'))
  const manifest = read('bundle.yaml')
  assertKeys(manifest, ['root', 'paths', 'components'], 'bundle.yaml')
  const listed = ['bundle.yaml', manifest.root, ...manifest.paths, ...manifest.components]
  if (new Set(listed).size !== listed.length) throw new Error('bundle.yaml 包含重复文件')
  for (const file of filesUnder(sourceRoot)) {
    if (!listed.includes(file)) throw new Error(`OpenAPI 清单外文件：${file}`)
  }

  const root = read(manifest.root)
  assertKeys(root, rootKeys, manifest.root)
  assertKeys(root.components, ['securitySchemes'], manifest.root)
  const paths = {}
  const components = { securitySchemes: root.components.securitySchemes }
  for (const file of manifest.paths) {
    const document = read(file)
    assertKeys(document, ['paths'], file)
    mergeMap(paths, document.paths, 'paths')
  }
  for (const file of manifest.components) {
    const document = read(file)
    assertKeys(document, ['components'], file)
    assertKeys(document.components, componentKeys, file)
    for (const [section, entries] of Object.entries(document.components)) {
      components[section] ??= {}
      mergeMap(components[section], entries, `components.${section}`)
    }
  }
  const orderedComponents = Object.fromEntries(componentKeys
    .filter(key => Object.hasOwn(components, key)).map(key => [key, components[key]]))
  const document = { ...root, paths, components: orderedComponents }
  const ordered = Object.fromEntries(['openapi', 'info', 'servers', 'tags', 'paths', 'components']
    .filter(key => Object.hasOwn(document, key)).map(key => [key, document[key]]))
  return banner + stringify(ordered, { lineWidth: 0 })
}

export function generateOpenApi(sourceRoot, outputPath, check = false) {
  const contents = bundleOpenApi(sourceRoot)
  if (check) {
    if (fs.readFileSync(outputPath, 'utf8') !== contents) {
      throw new Error('OpenAPI 单文件与分域源不同步，运行 pnpm generate:openapi')
    }
  } else {
    fs.writeFileSync(outputPath, contents, 'utf8')
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
  try {
    generateOpenApi(path.join(repositoryRoot, 'contracts/openapi/yumpoo-v1'),
      path.join(repositoryRoot, 'contracts/openapi/yumpoo-v1.yaml'), process.argv.includes('--check'))
    console.log(process.argv.includes('--check') ? 'OpenAPI 分域源与单文件一致' : 'OpenAPI 单文件已生成')
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
