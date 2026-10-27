import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)

const desktopPackagePath = path.join(
  repositoryRoot,
  'desktop',
  'desktop-shell',
  'package.json',
)

const manifestFields = [
  'schemaVersion',
  'milestone',
  'generatedAt',
  'sourceCommit',
  'platform',
  'arch',
  'electronVersion',
  'packageDirectory',
  'files',
]
const manifestFileFields = ['path', 'bytes', 'sha256']

const utcInstantPattern =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/
const sha256Pattern = /^[0-9a-f]{64}$/
const semverPattern = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/

const maximumManifestBytes = 2 * 1024 * 1024

function fail(message) {
  throw new Error(`DESKTOP 真实环境验证失败：${message}`)
}

function assert(condition, message) {
  if (!condition) fail(message)
}

function readJson(filePath, label) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch {
    fail(`${label} 不是有效 JSON`)
  }
}


function sameFields(value, expected) {
  return (
    Object.keys(value).sort().join('\n') === [...expected].sort().join('\n')
  )
}


function readBoundedFile(filePath, label, maximumBytes) {
  let stat
  try {
    stat = fs.lstatSync(filePath)
  } catch {
    fail(`${label} 不存在或不可读取`)
  }
  assert(stat.isFile() && !stat.isSymbolicLink(), `${label} 必须是普通文件`)
  assert(
    stat.size > 0 && stat.size <= maximumBytes,
    `${label} 大小不在安全范围内`,
  )
  return fs.readFileSync(filePath)
}


function validateBuildManifest(manifestPath, electronVersion) {
  const raw = readBoundedFile(
    manifestPath,
    'DESKTOP 构建 manifest',
    maximumManifestBytes,
  )
  let manifest
  try {
    manifest = JSON.parse(raw.toString('utf8'))
  } catch {
    fail('DESKTOP 构建 manifest 不是有效 JSON')
  }
  assert(
    manifest !== null && typeof manifest === 'object' && !Array.isArray(manifest),
    'DESKTOP 构建 manifest 必须是 JSON object',
  )
  assert(
    sameFields(manifest, manifestFields),
    'DESKTOP 构建 manifest 字段白名单不正确',
  )
  assert(manifest.schemaVersion === 1, 'DESKTOP 构建 manifest 版本不受支持')
  assert(manifest.milestone === 'DESKTOP', 'DESKTOP 构建 manifest milestone 不正确')
  assert(
    typeof manifest.generatedAt === 'string' &&
      utcInstantPattern.test(manifest.generatedAt) &&
      Number.isFinite(Date.parse(manifest.generatedAt)),
    'DESKTOP 构建 manifest generatedAt 不正确',
  )
  assert(/^[0-9a-f]{40}$/u.test(manifest.sourceCommit), 'DESKTOP 构建 manifest sourceCommit 不正确')
  assert(manifest.platform === 'win32', 'DESKTOP 构建 manifest 未证明 Windows')
  assert(manifest.arch === 'x64', 'DESKTOP 构建 manifest 未证明 x64')
  assert(
    manifest.electronVersion === electronVersion,
    'DESKTOP 构建 manifest Electron 版本与锁定版本不一致',
  )
  assert(
    typeof manifest.packageDirectory === 'string' &&
      manifest.packageDirectory === 'win-unpacked',
    'DESKTOP 构建 manifest packageDirectory 不正确',
  )
  assert(
    Array.isArray(manifest.files) &&
      manifest.files.length > 0 &&
      manifest.files.length <= 10_000,
    'DESKTOP 构建 manifest 文件清单大小不正确',
  )

  const manifestRoot = path.resolve(path.dirname(manifestPath))
  const packageRoot = path.resolve(manifestRoot, manifest.packageDirectory)
  assert(
    path.dirname(packageRoot).toLowerCase() === manifestRoot.toLowerCase(),
    'DESKTOP 构建 manifest packageDirectory 越出 manifest 目录',
  )

  const expectedPaths = []
  const caseFoldedPaths = new Set()
  for (const file of manifest.files) {
    assert(
      file !== null && typeof file === 'object' && !Array.isArray(file),
      'DESKTOP 构建 manifest 文件项不正确',
    )
    assert(
      sameFields(file, manifestFileFields),
      'DESKTOP 构建 manifest 文件字段白名单不正确',
    )
    assert(isSafeRelativeFilePath(file.path), 'DESKTOP 构建 manifest 文件路径不安全')
    assert(
      Number.isSafeInteger(file.bytes) && file.bytes >= 0,
      `DESKTOP 构建 manifest 文件大小不正确：${file.path}`,
    )
    assert(
      typeof file.sha256 === 'string' && sha256Pattern.test(file.sha256),
      `DESKTOP 构建 manifest 文件摘要不正确：${file.path}`,
    )
    const foldedPath = file.path.toLowerCase()
    assert(
      !caseFoldedPaths.has(foldedPath),
      `DESKTOP 构建 manifest 含重复文件路径：${file.path}`,
    )
    caseFoldedPaths.add(foldedPath)
    expectedPaths.push(file.path)
  }

  const actualPaths = listPackagedFiles(packageRoot)
  assert(
    actualPaths.length === expectedPaths.length &&
      actualPaths.every((value, index) => value === expectedPaths[index]),
    'DESKTOP 构建 manifest 未完整覆盖实际 packaged app 目录',
  )
  for (let index = 0; index < manifest.files.length; index += 1) {
    const expected = manifest.files[index]
    const absolutePath = path.join(packageRoot, ...expected.path.split('/'))
    const metadata = fs.lstatSync(absolutePath)
    assert(
      metadata.isFile() && !metadata.isSymbolicLink(),
      `packaged app 清单项不是普通文件：${expected.path}`,
    )
    assert(
      metadata.size === expected.bytes,
      `packaged app 文件大小与 manifest 不一致：${expected.path}`,
    )
    assert(
      sha256File(absolutePath) === expected.sha256,
      `packaged app 文件摘要与 manifest 不一致：${expected.path}`,
    )
  }

  return {
    raw,
    sha256: createHash('sha256').update(raw).digest('hex'),
  }
}

function isSafeRelativeFilePath(value) {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= 512 &&
    !value.includes('\\') &&
    !path.posix.isAbsolute(value) &&
    value
      .split('/')
      .every((segment) => segment && segment !== '.' && segment !== '..')
  )
}

function listPackagedFiles(root) {
  let rootMetadata
  try {
    rootMetadata = fs.lstatSync(root)
  } catch {
    fail('DESKTOP 构建 manifest 对应的 packaged app 目录不存在')
  }
  assert(
    rootMetadata.isDirectory() && !rootMetadata.isSymbolicLink(),
    'packaged app 根路径必须是普通目录',
  )
  const files = []

  function visit(directory) {
    const entries = fs.readdirSync(directory, { withFileTypes: true })
    entries.sort((left, right) => left.name.localeCompare(right.name, 'en'))
    for (const entry of entries) {
      const absolutePath = path.join(directory, entry.name)
      const metadata = fs.lstatSync(absolutePath)
      const relativePath = path.relative(root, absolutePath).replaceAll('\\', '/')
      assert(
        !metadata.isSymbolicLink(),
        `packaged app 不得包含符号链接：${relativePath}`,
      )
      if (metadata.isDirectory()) {
        visit(absolutePath)
      } else if (metadata.isFile()) {
        files.push(relativePath)
      } else {
        fail(`packaged app 含不支持的文件类型：${relativePath}`)
      }
    }
  }

  visit(root)
  return files
}

function sha256File(filePath) {
  const hash = createHash('sha256')
  const descriptor = fs.openSync(filePath, 'r')
  const buffer = Buffer.allocUnsafe(1024 * 1024)
  try {
    let bytesRead
    do {
      bytesRead = fs.readSync(descriptor, buffer, 0, buffer.length, null)
      if (bytesRead > 0) hash.update(buffer.subarray(0, bytesRead))
    } while (bytesRead > 0)
  } finally {
    fs.closeSync(descriptor)
  }
  return hash.digest('hex')
}


function validateManifestOnly(manifestPath) {
  assert(path.isAbsolute(manifestPath), '构建 manifest 校验路径必须是绝对路径')
  const desktopPackage = readJson(desktopPackagePath, 'Electron package.json')
  const electronVersion = desktopPackage?.devDependencies?.electron
  assert(
    typeof electronVersion === 'string' && semverPattern.test(electronVersion),
    'Electron 版本必须在 desktop package.json 中精确锁定',
  )
  validateBuildManifest(path.normalize(manifestPath), electronVersion)
  console.log('DESKTOP 构建 manifest 已覆盖并匹配完整 packaged app 目录。')
}


const outputRoot = path.resolve(process.env.YUMPOO_DESKTOP_OUTPUT_ROOT ?? path.join(repositoryRoot, 'desktop', 'desktop-shell', 'out', 'release'))
validateManifestOnly(path.join(outputRoot, 'desktop-artifact-manifest.json'))
