import fs from 'node:fs'
import path from 'node:path'
import { createHmac, timingSafeEqual } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'
import { runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const backendRoot = path.join(repositoryRoot, 'backend')
const evidenceRoot = path.join(repositoryRoot, 'evidence', 'm0-14')
const schemaPath = path.join(evidenceRoot, 'live-verification.schema.json')
const examplePath = path.join(evidenceRoot, 'live-verification.example.json')
const evidencePath = path.join(evidenceRoot, 'live-verification.json')
const receiptPath = path.join(backendRoot, 'target', 'm0-14-live-receipt.json')
const liveClass = 'M014FileSecurityLiveVerification'
const receiptFields = [
  'schemaVersion',
  'status',
  'verifiedAt',
  'filesystemType',
  'maxBytes',
  'bufferBytes',
  'scannerProvider',
  'checks',
  'signature',
]
const checkFields = [
  'configurationPreflight',
  'ntfsVerified',
  'sameVolumeVerified',
  'boundedExactLimitUpload',
  'limitPlusOneRejected',
  'interruptedUploadCleaned',
  'cleanSampleScanned',
  'eicarFailedClosed',
  'atomicMoveVerified',
  'pathsRedacted',
  'signedReceiptVerified',
]
const utcInstantPattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/
const sha256Pattern = /^[0-9a-f]{64}$/
const receiptClockSkewMilliseconds = 120_000
const insecureKeyMarkers = [
  'change-me',
  'changeme',
  'placeholder',
  'password',
  'secret-key',
]

function fail(message) {
  throw new Error(`M0-14 真实环境验证失败：${message}`)
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
  return ajv.compile(readJson(schemaPath, 'M0-14 证据 Schema'))
}

function validateEvidenceFiles() {
  const validate = validator()
  const example = readJson(examplePath, 'M0-14 证据示例')
  const evidence = readJson(evidencePath, 'M0-14 当前证据')
  assert(validate(example), 'M0-14 证据示例不符合 Schema')
  assert(validate(evidence), 'M0-14 当前证据不符合 Schema')
  assert(example.status === 'NOT_RUN', 'M0-14 证据示例必须保持 NOT_RUN')
  console.log(
    `M0-14 证据有效；当前真实 Defender/NTFS 状态：${evidence.status}`,
  )
}

function requiredEnvironment(name) {
  const value = process.env[name]
  assert(typeof value === 'string' && value.trim().length > 0, `缺少环境变量 ${name}`)
  return value.trim()
}

function preflight() {
  assert(process.platform === 'win32', '真实验证只能在 Windows 上运行')
  assert(
    requiredEnvironment('YUMPOO_M014_LIVE_ENABLED') === 'true',
    'YUMPOO_M014_LIVE_ENABLED 必须严格为 true',
  )
  assert(
    requiredEnvironment('YUMPOO_M014_ALLOW_EICAR') === 'true',
    'YUMPOO_M014_ALLOW_EICAR 必须严格为 true',
  )
  requiredEnvironment('YUMPOO_M014_LIVE_ROOT')
  requiredEnvironment('YUMPOO_M014_DEFENDER_EXECUTABLE')
  const keyValue = requiredEnvironment('YUMPOO_M014_EVIDENCE_HMAC_KEY')
  const key = Buffer.from(keyValue, 'utf8')
  const normalized = keyValue.toLowerCase()
  assert(key.length >= 32, '证据 HMAC 密钥必须至少 32 个 UTF-8 字节')
  assert(
    new Set([...keyValue]).size >= 8 &&
      !insecureKeyMarkers.some((marker) => normalized.includes(marker)),
    '证据 HMAC 密钥不符合强度策略',
  )
  return key
}

function sameFields(value, expected) {
  return (
    Object.keys(value).sort().join('\n') === [...expected].sort().join('\n')
  )
}

function canonicalReceipt(receipt) {
  return [
    'schemaVersion=1',
    'status=PASS',
    `verifiedAt=${receipt.verifiedAt}`,
    `filesystemType=${receipt.filesystemType}`,
    'maxBytes=104857600',
    'bufferBytes=65536',
    'scannerProvider=MICROSOFT_DEFENDER',
    ...checkFields.map(
      (name) => `checks.${name}=${String(receipt.checks[name])}`,
    ),
  ].join('\n')
}

function validateReceipt(receipt, hmacKey, startedAt) {
  assert(
    receipt !== null && typeof receipt === 'object' && !Array.isArray(receipt),
    'Maven 收据必须是 JSON object',
  )
  assert(sameFields(receipt, receiptFields), 'Maven 收据字段白名单不正确')
  assert(receipt.schemaVersion === 1, 'Maven 收据 schemaVersion 不受支持')
  assert(receipt.status === 'PASS', 'Maven 收据状态不是 PASS')
  assert(
    utcInstantPattern.test(receipt.verifiedAt) &&
      Number.isFinite(Date.parse(receipt.verifiedAt)),
    'Maven 收据 verifiedAt 不是 RFC 3339 UTC 时刻',
  )
  const verifiedAt = Date.parse(receipt.verifiedAt)
  assert(
    verifiedAt >= startedAt - receiptClockSkewMilliseconds &&
      verifiedAt <= Date.now() + receiptClockSkewMilliseconds,
    'Maven 收据不属于本次验证时间窗',
  )
  assert(receipt.filesystemType === 'NTFS', 'Maven 收据未证明 NTFS')
  assert(receipt.maxBytes === 104857600, 'Maven 收据 maxBytes 不正确')
  assert(receipt.bufferBytes === 65536, 'Maven 收据 bufferBytes 不正确')
  assert(
    receipt.scannerProvider === 'MICROSOFT_DEFENDER',
    'Maven 收据扫描器不正确',
  )
  assert(
    receipt.checks !== null &&
      typeof receipt.checks === 'object' &&
      !Array.isArray(receipt.checks) &&
      sameFields(receipt.checks, checkFields),
    'Maven 收据 checks 字段白名单不正确',
  )
  for (const name of checkFields) {
    assert(receipt.checks[name] === true, `Maven 收据检查 ${name} 未通过`)
  }
  assert(sha256Pattern.test(receipt.signature), 'Maven 收据签名格式不正确')
  const expected = Buffer.from(
    createHmac('sha256', hmacKey)
      .update('m0-14-receipt\0', 'utf8')
      .update(canonicalReceipt(receipt), 'utf8')
      .digest('hex'),
    'ascii',
  )
  const actual = Buffer.from(receipt.signature, 'ascii')
  assert(
    actual.length === expected.length && timingSafeEqual(actual, expected),
    'Maven 收据签名校验失败',
  )
  return receipt
}

function runMaven() {
  if (process.platform === 'win32') {
    runSync(
      'cmd.exe',
      ['/d', '/s', '/c', `mvnw.cmd -q -Dtest=${liveClass} test`],
      { cwd: backendRoot },
    )
  } else {
    runSync('./mvnw', ['-q', `-Dtest=${liveClass}`, 'test'], {
      cwd: backendRoot,
    })
  }
}

function writeEvidence(receipt) {
  const evidence = {
    schemaVersion: 1,
    milestone: 'M0-14',
    status: 'PASS',
    verifiedAt: receipt.verifiedAt,
    filesystemType: receipt.filesystemType,
    maxBytes: receipt.maxBytes,
    bufferBytes: receipt.bufferBytes,
    scannerProvider: receipt.scannerProvider,
    checks: receipt.checks,
  }
  const validate = validator()
  assert(validate(evidence), '即将写入的 M0-14 证据不符合 Schema')
  const temporary = `${evidencePath}.tmp-${process.pid}`
  fs.writeFileSync(temporary, `${JSON.stringify(evidence, null, 2)}\n`, {
    encoding: 'utf8',
    flag: 'wx',
  })
  fs.renameSync(temporary, evidencePath)
}

function runLive() {
  const key = preflight()
  fs.rmSync(receiptPath, { force: true })
  const startedAt = Date.now()
  try {
    runMaven()
    const receipt = validateReceipt(
      readJson(receiptPath, 'M0-14 Maven 收据'),
      key,
      startedAt,
    )
    writeEvidence(receipt)
    validateEvidenceFiles()
    console.log('M0-14 Defender/NTFS 真实验证已通过并原子更新证据。')
  } finally {
    fs.rmSync(receiptPath, { force: true })
  }
}

if (process.argv.includes('--validate-evidence')) {
  validateEvidenceFiles()
} else {
  runLive()
}
