import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'

export const commitPattern = /^[0-9a-f]{40}$/u
export const sha256Pattern = /^[0-9a-f]{64}$/u

export function failM018(message) {
  throw new Error(`M0-18 验证失败：${message}`)
}

export function assertM018(condition, message) {
  if (!condition) failM018(message)
}

export function readJson(file, label = path.basename(file)) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch {
    failM018(`${label} 不是有效 JSON`)
  }
}

export function schemaValidator(schemaFile) {
  const ajv = new Ajv({ allErrors: true, strict: false })
  addFormats(ajv)
  return ajv.compile(readJson(schemaFile, path.basename(schemaFile)))
}

export function assertSchema(schemaFile, value, label) {
  const validate = schemaValidator(schemaFile)
  assertM018(
    validate(value),
    `${label} 不符合 ${path.basename(schemaFile)}：${formatAjvErrors(validate.errors)}`,
  )
}

export function formatAjvErrors(errors = []) {
  return (errors ?? [])
    .map((error) => `${error.instancePath || '/'} ${error.message}`)
    .join('; ')
}

export function validateGitRef(value) {
  assertM018(typeof value === 'string' && value.length > 0, 'Git 基线 ref 不能为空')
  assertM018(value.length <= 200, 'Git 基线 ref 过长')
  assertM018(!value.startsWith('-'), 'Git 基线 ref 不得以连字符开头')
  assertM018(
    /^[A-Za-z0-9][A-Za-z0-9._/-]*$/u.test(value) &&
      !value.includes('..') &&
      !value.includes('//') &&
      !value.endsWith('/') &&
      !value.includes('@{'),
    'Git 基线 ref 格式不安全',
  )
  assertM018(!/^0{40}$/u.test(value), 'Git 基线 commit 不得为全零值')
  return value
}

export function runGit(repositoryRoot, args, label) {
  const result = spawnSync('git', args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  })
  assertM018(result.status === 0, `${label}：${String(result.stderr || '').trim() || 'git 执行失败'}`)
  return result.stdout
}

export function resolveGitCommit(repositoryRoot, reference) {
  const safeReference = validateGitRef(reference)
  const commit = runGit(
    repositoryRoot,
    ['rev-parse', '--verify', '--end-of-options', `${safeReference}^{commit}`],
    `无法解析 Git 基线 ${safeReference}`,
  ).trim()
  assertM018(commitPattern.test(commit), 'Git 基线未解析为完整 commit')
  return commit
}

export function gitHead(repositoryRoot) {
  const commit = runGit(repositoryRoot, ['rev-parse', 'HEAD'], '无法读取当前 Git HEAD').trim()
  assertM018(commitPattern.test(commit), '当前 Git HEAD 格式无效')
  return commit
}

export function gitObject(repositoryRoot, commit, objectPath) {
  assertM018(commitPattern.test(commit), 'Git 对象 commit 格式无效')
  assertM018(isSafeRelativePath(objectPath), 'Git 对象路径不安全')
  return runGit(repositoryRoot, ['show', `${commit}:${objectPath}`], `Git 基线缺少 ${objectPath}`)
}

export function sha256Buffer(value) {
  return createHash('sha256').update(value).digest('hex')
}

export function sha256File(file) {
  const hash = createHash('sha256')
  const descriptor = fs.openSync(file, 'r')
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

export function isSafeRelativePath(value) {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= 512 &&
    !value.includes('\\') &&
    !value.includes(':') &&
    !path.posix.isAbsolute(value) &&
    value.split('/').every((segment) => segment && segment !== '.' && segment !== '..')
  )
}

export function assertDirectoryTargetWithin(parent, candidate, label) {
  const resolvedParent = path.resolve(parent)
  const resolvedCandidate = path.resolve(candidate)
  assertM018(
    resolvedCandidate.startsWith(`${resolvedParent}${path.sep}`),
    `${label} 必须位于 ${path.basename(resolvedParent)} 目录内`,
  )
  const metadata = fs.lstatSync(resolvedCandidate, { throwIfNoEntry: false })
  assertM018(
    !metadata || (metadata.isDirectory() && !metadata.isSymbolicLink()),
    `${label} 已存在但不是普通目录`,
  )
  return resolvedCandidate
}

export function collectRegularFiles(root) {
  const rootMetadata = fs.lstatSync(root)
  assertM018(rootMetadata.isDirectory() && !rootMetadata.isSymbolicLink(), '产物根路径必须是普通目录')
  const files = []

  function visit(directory) {
    const entries = fs.readdirSync(directory, { withFileTypes: true })
    entries.sort((left, right) => left.name.localeCompare(right.name, 'en'))
    for (const entry of entries) {
      const absolutePath = path.join(directory, entry.name)
      const metadata = fs.lstatSync(absolutePath)
      const relativePath = path.relative(root, absolutePath).replaceAll('\\', '/')
      assertM018(!metadata.isSymbolicLink(), `产物不得包含符号链接：${relativePath}`)
      if (metadata.isDirectory()) visit(absolutePath)
      else if (metadata.isFile()) files.push(absolutePath)
      else failM018(`产物包含不支持的文件类型：${relativePath}`)
    }
  }

  visit(root)
  return files
}

export function copyTree(source, destination, options = {}) {
  const metadata = fs.lstatSync(source)
  assertM018(metadata.isDirectory() && !metadata.isSymbolicLink(), `${options.label ?? '复制源'}必须是普通目录`)
  fs.mkdirSync(destination, { recursive: true })
  for (const entry of fs.readdirSync(source, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
    const from = path.join(source, entry.name)
    const to = path.join(destination, entry.name)
    const entryMetadata = fs.lstatSync(from)
    assertM018(!entryMetadata.isSymbolicLink(), `复制源不得包含符号链接：${entry.name}`)
    if (entryMetadata.isDirectory()) copyTree(from, to, options)
    else if (entryMetadata.isFile()) {
      assertM018(!(options.rejectSourceMaps && entry.name.endsWith('.map')), '复制源不得包含 source map')
      fs.copyFileSync(from, to)
    } else failM018(`复制源包含不支持的文件类型：${entry.name}`)
  }
}

export function fileRecords(root, files, roleForPath) {
  const caseFolded = new Set()
  const records = files.map((file) => {
    const relativePath = path.relative(root, file).replaceAll('\\', '/')
    assertM018(isSafeRelativePath(relativePath), `产物路径不安全：${relativePath}`)
    const folded = relativePath.toLocaleLowerCase('en-US')
    assertM018(!caseFolded.has(folded), `产物路径发生 Windows 大小写碰撞：${relativePath}`)
    caseFolded.add(folded)
    const role = roleForPath(relativePath)
    assertM018(typeof role === 'string' && role.length > 0, `产物角色无法识别：${relativePath}`)
    return {
      path: relativePath,
      role,
      bytes: fs.statSync(file).size,
      sha256: sha256File(file),
    }
  })
  records.sort((left, right) => left.path.localeCompare(right.path, 'en'))
  return records
}

export function assertExactPayload(root, manifest, manifestName, expectedCommit) {
  assertM018(manifest && typeof manifest === 'object' && !Array.isArray(manifest), 'handoff manifest 必须是 object')
  if (expectedCommit) assertM018(manifest.sourceCommit === expectedCommit, 'handoff manifest 未绑定当前测试 commit')
  const manifestPath = path.join(root, manifestName)
  const actualFiles = collectRegularFiles(root).filter((file) => path.resolve(file) !== path.resolve(manifestPath))
  const records = fileRecords(root, actualFiles, (relativePath) => {
    if (relativePath === 'server/yumpoo-server.jar') return 'SERVER_JAR'
    if (relativePath.startsWith('web/')) return 'WEB_ASSET'
    return ''
  })
  assertM018(JSON.stringify(records) === JSON.stringify(manifest.files), 'handoff manifest 与实际文件、角色、大小或 SHA-256 不一致')
  assertM018(records.some((record) => record.path === 'server/yumpoo-server.jar'), 'handoff 缺少后端 JAR')
  assertM018(records.some((record) => record.path === 'web/index.html'), 'handoff 缺少 Web index.html')
  return records
}

export function atomicWriteJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const temporary = `${file}.tmp-${process.pid}-${Date.now()}`
  fs.writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
    flag: 'wx',
  })
  fs.renameSync(temporary, file)
}

export function requireCommit(value, label) {
  assertM018(typeof value === 'string' && commitPattern.test(value), `${label} 必须是完整小写 commit`)
  return value
}

export function commandOutput(command, args, label, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', ...options })
  assertM018(result.status === 0, `${label}不可用`)
  return `${result.stdout ?? ''}\n${result.stderr ?? ''}`.trim()
}
