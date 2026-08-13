import fs from 'node:fs'
import path from 'node:path'
import {
  createHash,
  createHmac,
  timingSafeEqual,
} from 'node:crypto'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const evidenceRoot = path.join(repositoryRoot, 'evidence', 'm0-15')
const schemaPath = path.join(evidenceRoot, 'live-verification.schema.json')
const examplePath = path.join(evidenceRoot, 'live-verification.example.json')
const evidencePath = path.join(evidenceRoot, 'live-verification.json')
const desktopPackagePath = path.join(
  repositoryRoot,
  'desktop',
  'desktop-shell',
  'package.json',
)
const callbackPath = '/_m0/m0-15/wecom/callback'
const protocolCallback = 'yumpoo://auth/callback'
const finalEvidenceFields = [
  'schemaVersion',
  'milestone',
  'status',
  'verifiedAt',
  'operatingSystem',
  'architecture',
  'electronVersion',
  'protocolCallback',
  'buildManifestSha256',
  'checks',
]
const desktopReceiptFields = [
  ...finalEvidenceFields,
  'authReceiptSha256',
  'signature',
]
const authReceiptFields = [
  'schemaVersion',
  'status',
  'requestId',
  'corpFingerprint',
  'memberFingerprint',
  'verifiedAt',
  'signature',
]
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
const checkFields = [
  'configurationPreflight',
  'windowsX64Verified',
  'packagedAppLaunched',
  'buildManifestVerified',
  'productionHttpsOriginVerified',
  'sharedRemoteSpaVerified',
  'systemBrowserWeComLoginVerified',
  'customProtocolCallbackVerified',
  'handoffSingleUseVerified',
  'handoffExpiryVerified',
  'stateBindingVerified',
  'pkceBindingVerified',
  'rendererIsolationVerified',
  'navigationPolicyVerified',
  'dangerousIpcRejected',
  'sensitiveDataRedacted',
  'signedReceiptVerified',
]
const utcInstantPattern =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/
const sha256Pattern = /^[0-9a-f]{64}$/
const requestIdPattern = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/
const semverPattern = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/
const receiptMaxAgeMilliseconds = 15 * 60 * 1000
const receiptFutureSkewMilliseconds = 2 * 60 * 1000
const receiptPairSkewMilliseconds = 5 * 60 * 1000
const maximumReceiptBytes = 64 * 1024
const maximumManifestBytes = 2 * 1024 * 1024
const insecureKeyMarkers = [
  'change-me',
  'changeme',
  'placeholder',
  'password',
  'secret-key',
]

function fail(message) {
  throw new Error(`M0-15 真实环境验证失败：${message}`)
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

function validator() {
  const ajv = new Ajv({ allErrors: true, strict: false })
  addFormats(ajv)
  return ajv.compile(readJson(schemaPath, 'M0-15 证据 Schema'))
}

function sameFields(value, expected) {
  return (
    Object.keys(value).sort().join('\n') === [...expected].sort().join('\n')
  )
}

function validateEvidenceFiles() {
  const validate = validator()
  const example = readJson(examplePath, 'M0-15 证据示例')
  const evidence = readJson(evidencePath, 'M0-15 当前证据')
  assert(validate(example), 'M0-15 证据示例不符合 Schema')
  assert(validate(evidence), 'M0-15 当前证据不符合 Schema')
  assert(
    sameFields(example, finalEvidenceFields) &&
      sameFields(evidence, finalEvidenceFields),
    'M0-15 证据顶层字段白名单不正确',
  )
  assert(example.status === 'NOT_RUN', 'M0-15 证据示例必须保持 NOT_RUN')
  console.log(`M0-15 证据有效；当前真实桌面交接状态：${evidence.status}`)
}

function requiredEnvironment(name) {
  const value = process.env[name]
  assert(
    typeof value === 'string' && value.trim().length > 0,
    `缺少环境变量 ${name}`,
  )
  return value.trim()
}

function parseHttpsUrl(raw, label) {
  let url
  try {
    url = new URL(raw)
  } catch {
    fail(`${label} 必须是有效 URL`)
  }
  assert(url.protocol === 'https:', `${label} 必须使用 HTTPS`)
  assert(!url.username && !url.password, `${label} 不得包含用户名或密码`)
  assert(!url.search && !url.hash, `${label} 不得包含 query 或 fragment`)
  return url
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

function absoluteInputPath(name) {
  const value = requiredEnvironment(name)
  assert(path.isAbsolute(value), `${name} 必须使用绝对路径`)
  return path.normalize(value)
}

function receiptPathsAvailableForCleanup() {
  const protectedPaths = [
    schemaPath,
    examplePath,
    evidencePath,
    desktopPackagePath,
  ]
  const configuredManifest = process.env.YUMPOO_M015_BUILD_MANIFEST_PATH?.trim()
  if (configuredManifest && path.isAbsolute(configuredManifest)) {
    protectedPaths.push(path.normalize(configuredManifest))
  }
  const protectedKeys = new Set(
    protectedPaths.map((value) => path.resolve(value).toLowerCase()),
  )
  const cleanupPaths = new Map()
  for (const name of [
    'YUMPOO_M015_AUTH_RECEIPT_PATH',
    'YUMPOO_M015_DESKTOP_RECEIPT_PATH',
  ]) {
    const value = process.env[name]?.trim()
    if (!value || !path.isAbsolute(value)) continue
    const normalized = path.normalize(value)
    const key = path.resolve(normalized).toLowerCase()
    if (!protectedKeys.has(key)) cleanupPaths.set(key, normalized)
  }
  return [...cleanupPaths.values()]
}

function preflight() {
  assert(process.platform === 'win32', '真实验证只能在 Windows 上运行')
  assert(process.arch === 'x64', '真实验证只能在 Windows x64 上运行')

  const profiles = requiredEnvironment('SPRING_PROFILES_ACTIVE')
    .split(',')
    .map((profile) => profile.trim())
  assert(profiles.includes('m0-15-live'), '必须启用 m0-15-live profile')
  assert(
    requiredEnvironment('YUMPOO_M015_WECOM_ENABLED') === 'true',
    'YUMPOO_M015_WECOM_ENABLED 必须严格为 true',
  )

  requiredEnvironment('YUMPOO_M015_WECOM_CORP_ID')
  assert(
    /^\d+$/.test(requiredEnvironment('YUMPOO_M015_WECOM_AGENT_ID')),
    'YUMPOO_M015_WECOM_AGENT_ID 必须是十进制整数',
  )
  const appSecretValue = requiredEnvironment('YUMPOO_M015_WECOM_APP_SECRET')
  const allowedMembers = requiredEnvironment(
    'YUMPOO_M015_WECOM_ALLOWED_MEMBER_IDS',
  ).split(',')
  assert(
    allowedMembers.every((memberId) => memberId.trim().length > 0),
    'YUMPOO_M015_WECOM_ALLOWED_MEMBER_IDS 不得包含空成员',
  )

  const callbackUrl = parseHttpsUrl(
    requiredEnvironment('YUMPOO_M015_WECOM_CALLBACK_URI'),
    '企微 callback URI',
  )
  assert(callbackUrl.pathname === callbackPath, '企微 callback URI 路径不正确')
  const liveBaseUrl = parseHttpsUrl(
    requiredEnvironment('YUMPOO_M015_LIVE_BASE_URL'),
    'M0-15 live base URL',
  )
  assert(liveBaseUrl.pathname === '/', 'M0-15 live base URL 不得包含路径')
  const webUrl = parseHttpsUrl(
    requiredEnvironment('YUMPOO_WEB_URL'),
    'Electron Web URL',
  )
  assert(
    callbackUrl.origin === liveBaseUrl.origin &&
      webUrl.origin === liveBaseUrl.origin,
    'callback、live base URL 与 Electron Web URL 必须同源',
  )

  const keyValue = requiredEnvironment('YUMPOO_M015_EVIDENCE_HMAC_KEY')
  const key = Buffer.from(keyValue, 'utf8')
  const appSecret = Buffer.from(appSecretValue, 'utf8')
  const normalizedKey = keyValue.toLowerCase()
  assert(key.length >= 32, '证据 HMAC 密钥必须至少 32 个 UTF-8 字节')
  assert(
    new Set([...keyValue]).size >= 8 &&
      !insecureKeyMarkers.some((marker) => normalizedKey.includes(marker)),
    '证据 HMAC 密钥不符合强度策略',
  )
  assert(
    key.length !== appSecret.length || !timingSafeEqual(key, appSecret),
    '证据 HMAC 密钥必须独立于企微应用 Secret',
  )

  const authReceiptPath = absoluteInputPath('YUMPOO_M015_AUTH_RECEIPT_PATH')
  const desktopReceiptPath = absoluteInputPath(
    'YUMPOO_M015_DESKTOP_RECEIPT_PATH',
  )
  const manifestPath = absoluteInputPath('YUMPOO_M015_BUILD_MANIFEST_PATH')
  const protectedPaths = [
    schemaPath,
    examplePath,
    evidencePath,
    manifestPath,
    desktopPackagePath,
  ].map((value) => path.resolve(value).toLowerCase())
  assert(
    path.resolve(authReceiptPath).toLowerCase() !==
      path.resolve(desktopReceiptPath).toLowerCase(),
    '认证收据与桌面收据必须是不同文件',
  )
  for (const receiptPath of [authReceiptPath, desktopReceiptPath]) {
    assert(
      !protectedPaths.includes(path.resolve(receiptPath).toLowerCase()),
      '短期收据不得覆盖证据、Schema 或构建 manifest',
    )
  }

  const desktopPackage = readJson(desktopPackagePath, 'Electron package.json')
  const electronVersion = desktopPackage?.devDependencies?.electron
  assert(
    typeof electronVersion === 'string' && semverPattern.test(electronVersion),
    'Electron 版本必须在 desktop package.json 中精确锁定',
  )

  return {
    authReceiptPath,
    desktopReceiptPath,
    manifestPath,
    electronVersion,
    key,
  }
}

function parseBoundedJson(filePath, label) {
  const raw = readBoundedFile(filePath, label, maximumReceiptBytes)
  try {
    return { raw, value: JSON.parse(raw.toString('utf8')) }
  } catch {
    fail(`${label} 不是有效 JSON`)
  }
}

function validateBuildManifest(manifestPath, electronVersion) {
  const raw = readBoundedFile(
    manifestPath,
    'M0-15 构建 manifest',
    maximumManifestBytes,
  )
  let manifest
  try {
    manifest = JSON.parse(raw.toString('utf8'))
  } catch {
    fail('M0-15 构建 manifest 不是有效 JSON')
  }
  assert(
    manifest !== null && typeof manifest === 'object' && !Array.isArray(manifest),
    'M0-15 构建 manifest 必须是 JSON object',
  )
  assert(
    sameFields(manifest, manifestFields),
    'M0-15 构建 manifest 字段白名单不正确',
  )
  assert(manifest.schemaVersion === 1, 'M0-15 构建 manifest 版本不受支持')
  assert(manifest.milestone === 'M0-15', 'M0-15 构建 manifest milestone 不正确')
  assert(
    typeof manifest.generatedAt === 'string' &&
      utcInstantPattern.test(manifest.generatedAt) &&
      Number.isFinite(Date.parse(manifest.generatedAt)),
    'M0-15 构建 manifest generatedAt 不正确',
  )
  assert(/^[0-9a-f]{40}$/u.test(manifest.sourceCommit), 'M0-15 构建 manifest sourceCommit 不正确')
  assert(manifest.platform === 'win32', 'M0-15 构建 manifest 未证明 Windows')
  assert(manifest.arch === 'x64', 'M0-15 构建 manifest 未证明 x64')
  assert(
    manifest.electronVersion === electronVersion,
    'M0-15 构建 manifest Electron 版本与锁定版本不一致',
  )
  assert(
    typeof manifest.packageDirectory === 'string' &&
      manifest.packageDirectory === 'Yumpoo Desktop-win32-x64',
    'M0-15 构建 manifest packageDirectory 不正确',
  )
  assert(
    Array.isArray(manifest.files) &&
      manifest.files.length > 0 &&
      manifest.files.length <= 10_000,
    'M0-15 构建 manifest 文件清单大小不正确',
  )

  const manifestRoot = path.resolve(path.dirname(manifestPath))
  const packageRoot = path.resolve(manifestRoot, manifest.packageDirectory)
  assert(
    path.dirname(packageRoot).toLowerCase() === manifestRoot.toLowerCase(),
    'M0-15 构建 manifest packageDirectory 越出 manifest 目录',
  )

  const expectedPaths = []
  const caseFoldedPaths = new Set()
  for (const file of manifest.files) {
    assert(
      file !== null && typeof file === 'object' && !Array.isArray(file),
      'M0-15 构建 manifest 文件项不正确',
    )
    assert(
      sameFields(file, manifestFileFields),
      'M0-15 构建 manifest 文件字段白名单不正确',
    )
    assert(isSafeRelativeFilePath(file.path), 'M0-15 构建 manifest 文件路径不安全')
    assert(
      Number.isSafeInteger(file.bytes) && file.bytes >= 0,
      `M0-15 构建 manifest 文件大小不正确：${file.path}`,
    )
    assert(
      typeof file.sha256 === 'string' && sha256Pattern.test(file.sha256),
      `M0-15 构建 manifest 文件摘要不正确：${file.path}`,
    )
    const foldedPath = file.path.toLowerCase()
    assert(
      !caseFoldedPaths.has(foldedPath),
      `M0-15 构建 manifest 含重复文件路径：${file.path}`,
    )
    caseFoldedPaths.add(foldedPath)
    expectedPaths.push(file.path)
  }

  const actualPaths = listPackagedFiles(packageRoot)
  assert(
    actualPaths.length === expectedPaths.length &&
      actualPaths.every((value, index) => value === expectedPaths[index]),
    'M0-15 构建 manifest 未完整覆盖实际 packaged app 目录',
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
    fail('M0-15 构建 manifest 对应的 packaged app 目录不存在')
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

function assertRecentUtcInstant(value, label) {
  assert(
    typeof value === 'string' &&
      utcInstantPattern.test(value) &&
      Number.isFinite(Date.parse(value)),
    `${label} 不是 RFC 3339 UTC 时刻`,
  )
  const timestamp = Date.parse(value)
  const now = Date.now()
  assert(
    timestamp >= now - receiptMaxAgeMilliseconds &&
      timestamp <= now + receiptFutureSkewMilliseconds,
    `${label} 不属于允许的短期时间窗`,
  )
  return timestamp
}

function canonicalAuthReceipt(receipt) {
  return [
    'schemaVersion=1',
    'status=PASS',
    `requestId=${receipt.requestId}`,
    `corpFingerprint=${receipt.corpFingerprint}`,
    `memberFingerprint=${receipt.memberFingerprint}`,
    `verifiedAt=${receipt.verifiedAt}`,
  ].join('\n')
}

function validateAuthReceipt(receipt, key) {
  assert(
    receipt !== null && typeof receipt === 'object' && !Array.isArray(receipt),
    '认证收据必须是 JSON object',
  )
  assert(sameFields(receipt, authReceiptFields), '认证收据字段白名单不正确')
  assert(receipt.schemaVersion === 1, '认证收据 schemaVersion 不受支持')
  assert(receipt.status === 'PASS', '认证收据状态不是 PASS')
  assert(requestIdPattern.test(receipt.requestId), '认证收据 requestId 格式不正确')
  assert(
    sha256Pattern.test(receipt.corpFingerprint) &&
      sha256Pattern.test(receipt.memberFingerprint),
    '认证收据身份指纹格式不正确',
  )
  const verifiedAt = assertRecentUtcInstant(
    receipt.verifiedAt,
    '认证收据 verifiedAt',
  )
  assert(sha256Pattern.test(receipt.signature), '认证收据签名格式不正确')
  const expected = Buffer.from(
    createHmac('sha256', key)
      .update(canonicalAuthReceipt(receipt), 'utf8')
      .digest('hex'),
    'ascii',
  )
  const actual = Buffer.from(receipt.signature, 'ascii')
  assert(
    actual.length === expected.length && timingSafeEqual(actual, expected),
    '认证收据签名校验失败',
  )
  return verifiedAt
}

function canonicalDesktopReceipt(receipt) {
  return [
    'schemaVersion=1',
    'milestone=M0-15',
    'status=PASS',
    `verifiedAt=${receipt.verifiedAt}`,
    'operatingSystem=WINDOWS',
    'architecture=x64',
    `electronVersion=${receipt.electronVersion}`,
    `protocolCallback=${protocolCallback}`,
    `buildManifestSha256=${receipt.buildManifestSha256}`,
    `authReceiptSha256=${receipt.authReceiptSha256}`,
    ...checkFields.map(
      (name) => `checks.${name}=${String(receipt.checks[name])}`,
    ),
  ].join('\n')
}

function validateDesktopReceipt(
  receipt,
  key,
  electronVersion,
  authReceiptSha256,
  manifestSha256,
  authVerifiedAt,
) {
  assert(
    receipt !== null && typeof receipt === 'object' && !Array.isArray(receipt),
    '桌面收据必须是 JSON object',
  )
  assert(
    sameFields(receipt, desktopReceiptFields),
    '桌面收据字段白名单不正确',
  )
  assert(receipt.schemaVersion === 1, '桌面收据 schemaVersion 不受支持')
  assert(receipt.milestone === 'M0-15', '桌面收据 milestone 不正确')
  assert(receipt.status === 'PASS', '桌面收据状态不是 PASS')
  const verifiedAt = assertRecentUtcInstant(
    receipt.verifiedAt,
    '桌面收据 verifiedAt',
  )
  assert(
    Math.abs(verifiedAt - authVerifiedAt) <= receiptPairSkewMilliseconds,
    '认证收据与桌面收据不属于同一验证时间窗',
  )
  assert(receipt.operatingSystem === 'WINDOWS', '桌面收据未证明 Windows')
  assert(receipt.architecture === 'x64', '桌面收据未证明 x64')
  assert(
    receipt.electronVersion === electronVersion,
    '桌面收据 Electron 版本与锁定版本不一致',
  )
  assert(
    receipt.protocolCallback === protocolCallback,
    '桌面收据协议回调不正确',
  )
  assert(
    receipt.authReceiptSha256 === authReceiptSha256,
    '桌面收据未绑定本次认证收据',
  )
  assert(
    receipt.buildManifestSha256 === manifestSha256,
    '桌面收据与构建 manifest 摘要不一致',
  )
  assert(
    receipt.checks !== null &&
      typeof receipt.checks === 'object' &&
      !Array.isArray(receipt.checks) &&
      sameFields(receipt.checks, checkFields),
    '桌面收据 checks 字段白名单不正确',
  )
  for (const name of checkFields) {
    assert(receipt.checks[name] === true, `桌面收据检查 ${name} 未通过`)
  }
  assert(sha256Pattern.test(receipt.signature), '桌面收据签名格式不正确')
  const expected = Buffer.from(
    createHmac('sha256', key)
      .update('m0-15-desktop-receipt\0', 'utf8')
      .update(canonicalDesktopReceipt(receipt), 'utf8')
      .digest('hex'),
    'ascii',
  )
  const actual = Buffer.from(receipt.signature, 'ascii')
  assert(
    actual.length === expected.length && timingSafeEqual(actual, expected),
    '桌面收据签名校验失败',
  )
  return receipt
}

function prepareEvidence(receipt) {
  const evidence = Object.fromEntries(
    finalEvidenceFields.map((name) => [name, receipt[name]]),
  )
  const validate = validator()
  assert(validate(evidence), '即将写入的 M0-15 证据不符合 Schema')
  const temporaryPath = `${evidencePath}.tmp-${process.pid}-${Date.now()}`
  fs.writeFileSync(temporaryPath, `${JSON.stringify(evidence, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
    flag: 'wx',
  })
  return temporaryPath
}

function deleteShortLivedReceipts(receiptPaths) {
  let cleanupFailed = false
  for (const receiptPath of receiptPaths) {
    try {
      fs.rmSync(receiptPath, { force: true })
      if (fs.existsSync(receiptPath)) cleanupFailed = true
    } catch {
      cleanupFailed = true
    }
  }
  assert(!cleanupFailed, '短期收据无法安全删除，原证据保持不变')
}

function runLive() {
  const cleanupReceiptPaths = receiptPathsAvailableForCleanup()
  let temporaryEvidencePath
  let evidenceCommitted = false
  try {
    validateEvidenceFiles()
    const configuration = preflight()
    const receiptPaths = [
      configuration.authReceiptPath,
      configuration.desktopReceiptPath,
    ]
    const manifest = validateBuildManifest(
      configuration.manifestPath,
      configuration.electronVersion,
    )
    const authReceipt = parseBoundedJson(
      configuration.authReceiptPath,
      'M0-15 认证收据',
    )
    const desktopReceipt = parseBoundedJson(
      configuration.desktopReceiptPath,
      'M0-15 桌面收据',
    )
    const authReceiptSha256 = createHash('sha256')
      .update(authReceipt.raw)
      .digest('hex')
    const authVerifiedAt = validateAuthReceipt(
      authReceipt.value,
      configuration.key,
    )
    const verifiedReceipt = validateDesktopReceipt(
      desktopReceipt.value,
      configuration.key,
      configuration.electronVersion,
      authReceiptSha256,
      manifest.sha256,
      authVerifiedAt,
    )
    temporaryEvidencePath = prepareEvidence(verifiedReceipt)
    deleteShortLivedReceipts(receiptPaths)
    fs.renameSync(temporaryEvidencePath, evidencePath)
    temporaryEvidencePath = undefined
    evidenceCommitted = true
    try {
      console.log('M0-15 真实桌面登录交接已通过并原子更新脱敏证据。')
    } catch {
      // 证据已提交，输出流关闭不得再把 PASS 变成失败。
    }
  } finally {
    if (!evidenceCommitted) {
      try {
        deleteShortLivedReceipts(cleanupReceiptPaths)
      } finally {
        if (temporaryEvidencePath) {
          fs.rmSync(temporaryEvidencePath, { force: true })
        }
      }
    }
  }
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
  console.log('M0-15 构建 manifest 已覆盖并匹配完整 packaged app 目录。')
}

const argumentsList = process.argv.slice(2)
if (
  argumentsList.length === 1 &&
  argumentsList[0] === '--validate-evidence'
) {
  validateEvidenceFiles()
} else if (
  argumentsList.length === 2 &&
  argumentsList[0] === '--validate-manifest'
) {
  validateManifestOnly(argumentsList[1])
} else if (argumentsList.length === 0) {
  runLive()
} else {
  fail('仅支持无参数真实验证、--validate-evidence 或 --validate-manifest <绝对路径>')
}
