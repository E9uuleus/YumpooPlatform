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
const evidenceRoot = path.join(repositoryRoot, 'evidence', 'm0-13')
const evidenceSchemaPath = path.join(
  evidenceRoot,
  'live-verification.schema.json',
)
const evidenceExamplePath = path.join(
  evidenceRoot,
  'live-verification.example.json',
)
const evidencePath = path.join(evidenceRoot, 'live-verification.json')
const m012EvidenceRoot = path.join(repositoryRoot, 'evidence', 'm0-12')
const m012EvidenceSchemaPath = path.join(
  m012EvidenceRoot,
  'live-verification.schema.json',
)
const m012EvidencePath = path.join(
  m012EvidenceRoot,
  'live-verification.json',
)
const backendRoot = path.join(repositoryRoot, 'backend')
const receiptPath = path.join(backendRoot, 'target', 'm0-13-live-receipt.json')
const liveVerificationClass = 'M013WeComDirectoryLiveVerification'
const receiptFields = [
  'schemaVersion',
  'status',
  'verifiedAt',
  'corpFingerprint',
  'snapshotFingerprint',
  'checks',
  'signature',
]
const receiptCheckFields = [
  'configurationPreflight',
  'realDirectoryRead',
  'providerPaginationObserved',
  'providerTerminalCursorOmissionConfirmed',
  'rerunIdempotent',
  'pageFailureSafe',
  'itemFailurePartialSafe',
  'syntheticDepartureDetected',
  'syntheticReturnReused',
  'secretsRedacted',
  'externalLimitsRecorded',
]
const fingerprintPattern = /^[0-9a-f]{64}$/
const utcInstantPattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/
const receiptClockSkewMilliseconds = 120_000
const databaseEnvironmentNames = new Set([
  'SPRING_DATASOURCE_URL',
  'SPRING_DATASOURCE_USERNAME',
  'SPRING_DATASOURCE_PASSWORD',
  'SPRING_FLYWAY_URL',
  'SPRING_FLYWAY_USER',
  'SPRING_FLYWAY_PASSWORD',
])
const insecureKeyMarkers = [
  'change-me',
  'changeme',
  'placeholder',
  'password',
  'secret-key',
]

function fail(message) {
  throw new Error(`M0-13 真实验证失败：${message}`)
}

function assert(condition, message) {
  if (!condition) {
    fail(message)
  }
}

function readJsonFile(filePath, label) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch {
    fail(`${label} 不是有效 JSON`)
  }
}

function jsonValidator(schemaPath, label) {
  const schema = readJsonFile(schemaPath, label)
  const ajv = new Ajv({ allErrors: true, strict: false })
  addFormats(ajv)
  return ajv.compile(schema)
}

function assertValidEvidence(validate, value, label) {
  assert(validate(value), `${label} 不符合证据 Schema`)
}

function validateEvidenceFiles() {
  const validate = jsonValidator(evidenceSchemaPath, 'M0-13 证据 Schema')
  const example = readJsonFile(evidenceExamplePath, 'M0-13 证据示例')
  const evidence = readJsonFile(evidencePath, 'M0-13 真实验证证据')
  assertValidEvidence(validate, example, 'M0-13 证据示例')
  assertValidEvidence(validate, evidence, 'M0-13 真实验证证据')
  assert(example.status === 'NOT_RUN', 'M0-13 证据示例必须保持 NOT_RUN')
  console.log('M0-13 证据 Schema、示例与当前证据均有效。')
}

function requiredEnvironment(name) {
  const value = process.env[name]
  assert(
    typeof value === 'string' && value.trim().length > 0,
    `缺少环境变量 ${name}`,
  )
  return value
}

function readM012DependencyStatus() {
  const validate = jsonValidator(m012EvidenceSchemaPath, 'M0-12 证据 Schema')
  const evidence = readJsonFile(m012EvidencePath, 'M0-12 真实验证证据')
  assertValidEvidence(validate, evidence, 'M0-12 真实验证证据')
  assert(
    evidence.status === 'NOT_RUN' || evidence.status === 'PASS',
    'M0-12 证据状态不受支持',
  )
  return evidence.status
}

function preflightConfiguration() {
  const requiredNames = [
    'SPRING_PROFILES_ACTIVE',
    'YUMPOO_M013_WECOM_ENABLED',
    'YUMPOO_M013_WECOM_CORP_ID',
    'YUMPOO_M013_WECOM_DIRECTORY_SECRET',
    'YUMPOO_M013_EVIDENCE_HMAC_KEY',
  ]
  for (const name of requiredNames) {
    requiredEnvironment(name)
  }

  const profiles = requiredEnvironment('SPRING_PROFILES_ACTIVE')
    .split(',')
    .map((profile) => profile.trim())
  assert(profiles.includes('m0-13-live'), '必须启用 m0-13-live profile')
  assert(
    requiredEnvironment('YUMPOO_M013_WECOM_ENABLED') === 'true',
    'YUMPOO_M013_WECOM_ENABLED 必须严格为 true',
  )

  const directorySecret = Buffer.from(
    requiredEnvironment('YUMPOO_M013_WECOM_DIRECTORY_SECRET'),
    'utf8',
  )
  const hmacKeyValue = requiredEnvironment('YUMPOO_M013_EVIDENCE_HMAC_KEY')
  const hmacKey = Buffer.from(hmacKeyValue, 'utf8')
  assert(hmacKey.length >= 32, '证据 HMAC 密钥必须至少为 32 个 UTF-8 字节')
  const normalizedHmacKey = hmacKeyValue.toLowerCase()
  assert(
    new Set([...hmacKeyValue]).size >= 8 &&
      !insecureKeyMarkers.some((marker) => normalizedHmacKey.includes(marker)),
    '证据 HMAC 密钥不符合强度策略',
  )
  assert(
    hmacKey.length !== directorySecret.length ||
      !timingSafeEqual(hmacKey, directorySecret),
    '证据 HMAC 密钥必须独立于企微通讯录 Secret',
  )

  return {
    hmacKey,
    m012DependencyStatus: readM012DependencyStatus(),
  }
}

function sameFields(value, expectedFields) {
  return (
    Object.keys(value).sort().join('\n') ===
    [...expectedFields].sort().join('\n')
  )
}

function canonicalReceipt(receipt) {
  return [
    'schemaVersion=1',
    'status=PASS',
    `verifiedAt=${receipt.verifiedAt}`,
    `corpFingerprint=${receipt.corpFingerprint}`,
    `snapshotFingerprint=${receipt.snapshotFingerprint}`,
    ...receiptCheckFields.map(
      (name) => `checks.${name}=${String(receipt.checks[name])}`,
    ),
  ].join('\n')
}

function validateReceipt(receipt, hmacKey, runStartedAt) {
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
    'Maven 收据 verifiedAt 必须是 RFC 3339 UTC 时刻',
  )
  const verifiedAt = Date.parse(receipt.verifiedAt)
  assert(
    verifiedAt >= runStartedAt - receiptClockSkewMilliseconds &&
      verifiedAt <= Date.now() + receiptClockSkewMilliseconds,
    'Maven 收据 verifiedAt 不属于本次验证时间窗',
  )
  assert(
    fingerprintPattern.test(receipt.corpFingerprint),
    'Maven 收据企业 HMAC 格式不正确',
  )
  assert(
    fingerprintPattern.test(receipt.snapshotFingerprint),
    'Maven 收据快照 HMAC 格式不正确',
  )
  assert(
    receipt.checks !== null &&
      typeof receipt.checks === 'object' &&
      !Array.isArray(receipt.checks) &&
      sameFields(receipt.checks, receiptCheckFields),
    'Maven 收据 checks 字段白名单不正确',
  )
  for (const name of receiptCheckFields) {
    assert(receipt.checks[name] === true, `Maven 收据检查 ${name} 未通过`)
  }
  assert(fingerprintPattern.test(receipt.signature), 'Maven 收据签名格式不正确')

  const expected = Buffer.from(
    createHmac('sha256', hmacKey)
      // 独立 receipt 域，不能与 corp/member/snapshot 指纹复用同一消息空间。
      .update('receipt\0', 'utf8')
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

function runMavenLiveVerification() {
  const childEnvironment = Object.fromEntries(
    Object.entries(process.env).filter(
      ([name]) => !databaseEnvironmentNames.has(name.toUpperCase()),
    ),
  )
  if (process.platform === 'win32') {
    runSync(
      'cmd.exe',
      [
        '/d',
        '/s',
        '/c',
        `mvnw.cmd -Dtest=${liveVerificationClass} test`,
      ],
      { cwd: backendRoot, env: childEnvironment },
    )
    return
  }
  runSync('./mvnw', [`-Dtest=${liveVerificationClass}`, 'test'], {
    cwd: backendRoot,
    env: childEnvironment,
  })
}

function passEvidence(receipt, m012DependencyStatus) {
  return {
    schemaVersion: 1,
    milestone: 'M0-13',
    status: 'PASS',
    verifiedAt: new Date(Date.parse(receipt.verifiedAt)).toISOString(),
    corpFingerprint: receipt.corpFingerprint,
    snapshotFingerprint: receipt.snapshotFingerprint,
    m012DependencyStatus,
    checks: {
      ...receipt.checks,
      signedReceiptVerified: true,
    },
  }
}

function writeEvidence(evidence) {
  const validate = jsonValidator(evidenceSchemaPath, 'M0-13 证据 Schema')
  assertValidEvidence(validate, evidence, '待写入的 M0-13 证据')
  const temporaryPath = `${evidencePath}.${process.pid}.tmp`
  try {
    fs.writeFileSync(temporaryPath, `${JSON.stringify(evidence, null, 2)}\n`, {
      encoding: 'utf8',
      mode: 0o600,
      flag: 'wx',
    })
    fs.renameSync(temporaryPath, evidencePath)
  } finally {
    fs.rmSync(temporaryPath, { force: true })
  }
}

function runLiveVerification() {
  const runStartedAt = Date.now()
  const { hmacKey, m012DependencyStatus } = preflightConfiguration()
  console.log('M0-13 配置预检通过；配置值和凭据不会输出。')

  let receipt
  try {
    fs.rmSync(receiptPath, { force: true })
    runMavenLiveVerification()
    assert(fs.existsSync(receiptPath), 'Maven 未生成预期收据')
    receipt = validateReceipt(
      readJsonFile(receiptPath, 'Maven 收据'),
      hmacKey,
      runStartedAt,
    )
  } finally {
    fs.rmSync(receiptPath, { force: true })
  }

  writeEvidence(passEvidence(receipt, m012DependencyStatus))
  console.log(
    'M0-13 真实验证 PASS；已写入脱敏 evidence/m0-13/live-verification.json。',
  )
}

const args = process.argv.slice(2)
try {
  if (args.length === 1 && args[0] === '--validate-evidence') {
    validateEvidenceFiles()
  } else if (args.length === 0) {
    runLiveVerification()
  } else {
    fail('仅支持无参数运行或 --validate-evidence')
  }
} catch (error) {
  const message = error instanceof Error ? error.message : '未知错误'
  console.error(message)
  process.exitCode = 1
}
