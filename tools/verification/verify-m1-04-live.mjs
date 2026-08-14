import fs from 'node:fs'
import path from 'node:path'
import { createHmac, timingSafeEqual } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'
import { runSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const backendRoot = path.join(repositoryRoot, 'backend')
const evidenceRoot = path.join(repositoryRoot, 'evidence', 'm1-04')
const schemaPath = path.join(evidenceRoot, 'live-verification.schema.json')
const examplePath = path.join(evidenceRoot, 'live-verification.example.json')
const evidencePath = path.join(evidenceRoot, 'live-verification.json')
const receiptPath = path.join(backendRoot, 'target', 'm1-04-live-receipt.json')
const liveClass = 'M104WeComDirectoryLiveVerification'
const checkNames = [
  'configurationPreflight',
  'directoryCredentialRead',
  'snapshotTerminationConfirmed',
  'profileCredentialRead',
  'departmentDictionaryRead',
  'requiredProfileVisible',
  'optionalVisibilityCaptured',
  'secretsRedacted',
]
const receiptFields = [
  'schemaVersion',
  'status',
  'verifiedAt',
  'corpFingerprint',
  'snapshotFingerprint',
  'checks',
  'signature',
]
const fingerprintPattern = /^[0-9a-f]{64}$/
const insecureMarkers = ['change-me', 'changeme', 'placeholder', 'password']

function fail(message) {
  throw new Error(`M1-04 真实验证失败：${message}`)
}

function assert(condition, message) {
  if (!condition) fail(message)
}

function readJson(file, label) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch {
    fail(`${label} 不是有效 JSON`)
  }
}

function validator() {
  const ajv = new Ajv({ allErrors: true, strict: false })
  addFormats(ajv)
  return ajv.compile(readJson(schemaPath, '证据 Schema'))
}

function validateEvidenceFiles() {
  const validate = validator()
  const example = readJson(examplePath, '证据示例')
  const evidence = readJson(evidencePath, '当前证据')
  assert(validate(example), '证据示例不符合 Schema')
  assert(validate(evidence), '当前证据不符合 Schema')
  assert(example.status === 'ENV_PENDING', '证据示例必须保持 ENV_PENDING')
  console.log('M1-04 live evidence Schema、示例与当前证据均有效。')
}

function required(name) {
  const value = process.env[name]
  assert(typeof value === 'string' && value.trim().length > 0, `缺少环境变量 ${name}`)
  return value.trim()
}

function sameBytes(first, second) {
  const a = Buffer.from(first, 'utf8')
  const b = Buffer.from(second, 'utf8')
  return a.length === b.length && timingSafeEqual(a, b)
}

function preflight() {
  const profiles = required('SPRING_PROFILES_ACTIVE').split(',').map((value) => value.trim())
  assert(profiles.includes('m1-04-live'), '必须启用 m1-04-live profile')
  assert(required('YUMPOO_M104_WECOM_ENABLED') === 'true', 'YUMPOO_M104_WECOM_ENABLED 必须严格为 true')
  const directorySecret = required('YUMPOO_M104_WECOM_DIRECTORY_SECRET')
  const profileSecret = required('YUMPOO_M104_WECOM_PROFILE_SECRET')
  const hmacValue = required('YUMPOO_M104_EVIDENCE_HMAC_KEY')
  const hmacKey = Buffer.from(hmacValue, 'utf8')
  assert(!sameBytes(directorySecret, profileSecret), '目录与成员资料 Secret 必须不同')
  assert(hmacKey.length >= 32, '证据 HMAC 密钥至少需要 32 个 UTF-8 字节')
  assert(new Set([...hmacValue]).size >= 8, '证据 HMAC 密钥复杂度不足')
  assert(!insecureMarkers.some((marker) => hmacValue.toLowerCase().includes(marker)), '证据 HMAC 密钥不符合强度策略')
  assert(!sameBytes(hmacValue, directorySecret) && !sameBytes(hmacValue, profileSecret), '证据 HMAC 密钥必须独立于企微 Secret')
  required('YUMPOO_M104_WECOM_CORP_ID')
  return hmacKey
}

function canonical(receipt) {
  const fields = [
    'm1-04-receipt-v1',
    receipt.status,
    receipt.verifiedAt,
    receipt.corpFingerprint,
    receipt.snapshotFingerprint,
    ...checkNames.map((name) => String(receipt.checks[name])),
  ]
  return fields.join('\0')
}

function verifyReceipt(hmacKey) {
  const receipt = readJson(receiptPath, 'Maven 收据')
  assert(Object.keys(receipt).sort().join() === [...receiptFields].sort().join(), 'Maven 收据字段白名单不匹配')
  assert(receipt.schemaVersion === 1 && receipt.status === 'PASS', 'Maven 收据状态无效')
  const verifiedAt = Date.parse(receipt.verifiedAt)
  assert(Number.isFinite(verifiedAt) && Math.abs(Date.now() - verifiedAt) <= 120_000, 'Maven 收据已过期')
  assert(fingerprintPattern.test(receipt.corpFingerprint), '企业指纹格式无效')
  assert(fingerprintPattern.test(receipt.snapshotFingerprint), '快照指纹格式无效')
  assert(Object.keys(receipt.checks).join() === checkNames.join(), 'Maven 收据检查项不匹配')
  assert(checkNames.every((name) => receipt.checks[name] === true), 'Maven 收据存在未通过检查')
  const expected = Buffer.from(createHmac('sha256', hmacKey).update(canonical(receipt), 'utf8').digest('hex'), 'ascii')
  const actual = Buffer.from(receipt.signature, 'ascii')
  assert(actual.length === expected.length && timingSafeEqual(actual, expected), 'Maven 收据签名校验失败')
  return receipt
}

function runLiveClass() {
  if (process.platform === 'win32') {
    runSync('cmd.exe', ['/d', '/s', '/c', `mvnw.cmd -Dtest=${liveClass} test`], {
      cwd: backendRoot,
      env: process.env,
    })
  } else {
    runSync('./mvnw', [`-Dtest=${liveClass}`, 'test'], { cwd: backendRoot, env: process.env })
  }
}

function writePass(receipt) {
  const evidence = {
    schemaVersion: 1,
    milestone: 'M1-04',
    status: 'PASS',
    verifiedAt: new Date(Date.parse(receipt.verifiedAt)).toISOString(),
    corpFingerprint: receipt.corpFingerprint,
    snapshotFingerprint: receipt.snapshotFingerprint,
    checks: { ...receipt.checks, signedReceiptVerified: true },
  }
  const validate = validator()
  assert(validate(evidence), '待写入证据不符合 Schema')
  const temporary = `${evidencePath}.tmp`
  fs.writeFileSync(temporary, `${JSON.stringify(evidence, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' })
  fs.renameSync(temporary, evidencePath)
}

validateEvidenceFiles()
if (process.argv.includes('--validate-evidence')) process.exit(0)

const hmacKey = preflight()
fs.rmSync(receiptPath, { force: true })
try {
  runLiveClass()
  const receipt = verifyReceipt(hmacKey)
  writePass(receipt)
  console.log('M1-04 真实企微凭据与目录资料读取验证 PASS。')
} finally {
  fs.rmSync(receiptPath, { force: true })
}
