import fs from 'node:fs'
import path from 'node:path'
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto'
import { createInterface } from 'node:readline/promises'
import { Writable } from 'node:stream'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const evidenceRoot = path.join(repositoryRoot, 'evidence', 'm0-12')
const evidenceSchemaPath = path.join(
  evidenceRoot,
  'live-verification.schema.json',
)
const evidenceExamplePath = path.join(
  evidenceRoot,
  'live-verification.example.json',
)
const evidencePath = path.join(evidenceRoot, 'live-verification.json')
const authorizePath = '/_m0/m0-12/wecom/authorize'
const callbackPath = '/_m0/m0-12/wecom/callback'
const nonceCookieName = '__Host-yumpoo-m012-oauth-nonce'
const receiptFields = [
  'schemaVersion',
  'status',
  'requestId',
  'corpFingerprint',
  'memberFingerprint',
  'verifiedAt',
  'signature',
]
const requestIdPattern = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/
const fingerprintPattern = /^[0-9a-f]{64}$/
const utcInstantPattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/
const receiptClockSkewMilliseconds = 120_000
const insecureKeyMarkers = [
  'change-me',
  'changeme',
  'placeholder',
  'password',
  'secret-key',
]
const invalidCode = `m012-invalid-${randomBytes(24).toString('base64url')}`

function fail(message) {
  throw new Error(`M0-12 真实验证失败：${message}`)
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

function evidenceValidator() {
  const schema = readJsonFile(evidenceSchemaPath, '证据 Schema')
  const ajv = new Ajv({ allErrors: true, strict: false })
  addFormats(ajv)
  return ajv.compile(schema)
}

function assertValidEvidence(validate, value, label) {
  assert(validate(value), `${label} 不符合证据 Schema`)
}

function validateEvidenceFiles() {
  const validate = evidenceValidator()
  const example = readJsonFile(evidenceExamplePath, '证据示例')
  const evidence = readJsonFile(evidencePath, '真实验证证据')
  assertValidEvidence(validate, example, '证据示例')
  assertValidEvidence(validate, evidence, '真实验证证据')
  assert(example.status === 'NOT_RUN', '证据示例必须保持 NOT_RUN')
  console.log('M0-12 证据 Schema、示例与当前证据均有效。')
}

function requiredEnvironment(name) {
  const value = process.env[name]
  assert(typeof value === 'string' && value.length > 0, `缺少环境变量 ${name}`)
  return value
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
  assert(!url.hash, `${label} 不得包含 fragment`)
  return url
}

function preflightConfiguration() {
  const requiredNames = [
    'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD',
    'SPRING_FLYWAY_URL',
    'SPRING_FLYWAY_USER',
    'SPRING_FLYWAY_PASSWORD',
    'YUMPOO_M012_WECOM_CORP_ID',
    'YUMPOO_M012_WECOM_AGENT_ID',
    'YUMPOO_M012_WECOM_APP_SECRET',
    'YUMPOO_M012_WECOM_CALLBACK_URI',
    'YUMPOO_M012_WECOM_ALLOWED_MEMBER_IDS',
    'YUMPOO_M012_EVIDENCE_HMAC_KEY',
  ]
  for (const name of requiredNames) {
    requiredEnvironment(name)
  }

  assert(
    requiredEnvironment('YUMPOO_M012_WECOM_ENABLED') === 'true',
    'YUMPOO_M012_WECOM_ENABLED 必须严格为 true',
  )
  const profiles = requiredEnvironment('SPRING_PROFILES_ACTIVE')
    .split(',')
    .map((profile) => profile.trim())
  assert(profiles.includes('m0-12-live'), '必须启用 m0-12-live profile')
  assert(
    /^\d+$/.test(requiredEnvironment('YUMPOO_M012_WECOM_AGENT_ID')),
    'YUMPOO_M012_WECOM_AGENT_ID 必须是十进制整数',
  )
  const corpId = requiredEnvironment('YUMPOO_M012_WECOM_CORP_ID')
  const agentId = requiredEnvironment('YUMPOO_M012_WECOM_AGENT_ID')
  const allowedMemberIds = requiredEnvironment(
    'YUMPOO_M012_WECOM_ALLOWED_MEMBER_IDS',
  ).split(',')
  assert(
    allowedMemberIds.every((memberId) => memberId.trim().length > 0),
    'YUMPOO_M012_WECOM_ALLOWED_MEMBER_IDS 不得包含空成员',
  )

  const hmacKeyValue = requiredEnvironment('YUMPOO_M012_EVIDENCE_HMAC_KEY')
  const hmacKey = Buffer.from(hmacKeyValue, 'utf8')
  assert(hmacKey.length >= 32, '证据 HMAC 密钥必须至少为 32 个 UTF-8 字节')
  const appSecret = Buffer.from(
    requiredEnvironment('YUMPOO_M012_WECOM_APP_SECRET'),
    'utf8',
  )
  assert(
    hmacKey.length !== appSecret.length || !timingSafeEqual(hmacKey, appSecret),
    '证据 HMAC 密钥必须独立于企微应用 Secret',
  )
  const normalizedHmacKey = hmacKeyValue.toLowerCase()
  assert(
    new Set([...hmacKeyValue]).size >= 8 &&
      !insecureKeyMarkers.some((marker) => normalizedHmacKey.includes(marker)),
    '证据 HMAC 密钥不符合强度策略',
  )

  const callbackUrl = parseHttpsUrl(
    requiredEnvironment('YUMPOO_M012_WECOM_CALLBACK_URI'),
    '企微 callback URI',
  )
  assert(callbackUrl.pathname === callbackPath, '企微 callback URI 路径不正确')
  assert(!callbackUrl.search, '企微 callback URI 不得包含 query')

  const configuredBase = process.env.YUMPOO_M012_LIVE_BASE_URL
  const baseUrl = configuredBase
    ? parseHttpsUrl(configuredBase, '真实验证 base URL')
    : new URL(callbackUrl.origin)
  assert(!baseUrl.search, '真实验证 base URL 不得包含 query')
  assert(baseUrl.pathname === '/', '真实验证 base URL 不得包含路径')
  assert(baseUrl.origin === callbackUrl.origin, 'base URL 必须与 callback URI 同源')

  return { baseUrl, callbackUrl, hmacKey, corpId, agentId }
}

function canonicalReceipt(receipt) {
  return [
    'schemaVersion=1',
    'status=PASS',
    `requestId=${receipt.requestId}`,
    `corpFingerprint=${receipt.corpFingerprint}`,
    `memberFingerprint=${receipt.memberFingerprint}`,
    `verifiedAt=${receipt.verifiedAt}`,
  ].join('\n')
}

function validateReceipt(raw, hmacKey, label, runStartedAt) {
  let receipt
  try {
    receipt = JSON.parse(raw)
  } catch {
    fail(`${label} 不是有效的单行 JSON`)
  }
  assert(
    receipt !== null && typeof receipt === 'object' && !Array.isArray(receipt),
    `${label} 必须是 JSON object`,
  )
  assert(
    Object.keys(receipt).join('\n') === receiptFields.join('\n'),
    `${label} 字段白名单或顺序不正确`,
  )
  assert(receipt.schemaVersion === 1, `${label} schemaVersion 不受支持`)
  assert(receipt.status === 'PASS', `${label} 状态不是 PASS`)
  assert(requestIdPattern.test(receipt.requestId), `${label} requestId 格式不正确`)
  assert(
    fingerprintPattern.test(receipt.corpFingerprint),
    `${label} 企业指纹格式不正确`,
  )
  assert(
    fingerprintPattern.test(receipt.memberFingerprint),
    `${label} 成员指纹格式不正确`,
  )
  assert(
    utcInstantPattern.test(receipt.verifiedAt) &&
      Number.isFinite(Date.parse(receipt.verifiedAt)),
    `${label} verifiedAt 必须是 RFC 3339 UTC 时刻`,
  )
  const verifiedAt = Date.parse(receipt.verifiedAt)
  assert(
    verifiedAt >= runStartedAt - receiptClockSkewMilliseconds &&
      verifiedAt <= Date.now() + receiptClockSkewMilliseconds,
    `${label} verifiedAt 不属于本次验证时间窗`,
  )
  assert(fingerprintPattern.test(receipt.signature), `${label} 签名格式不正确`)

  const expected = Buffer.from(
    createHmac('sha256', hmacKey)
      .update(canonicalReceipt(receipt), 'utf8')
      .digest('hex'),
    'ascii',
  )
  const actual = Buffer.from(receipt.signature, 'ascii')
  assert(
    actual.length === expected.length && timingSafeEqual(actual, expected),
    `${label} 签名校验失败`,
  )
  return receipt
}

async function safeFetch(url, options, label) {
  try {
    return await fetch(url, {
      redirect: 'manual',
      signal: AbortSignal.timeout(15_000),
      ...options,
    })
  } catch {
    fail(`${label} 请求失败`)
  }
}

function secureNonceCookie(response) {
  const setCookies =
    typeof response.headers.getSetCookie === 'function'
      ? response.headers.getSetCookie()
      : [response.headers.get('set-cookie')].filter(Boolean)
  const raw = setCookies.find((value) =>
    value.startsWith(`${nonceCookieName}=`),
  )
  assert(raw, '授权入口未设置精确的 M0-12 __Host- nonce Cookie')
  const parts = raw.split(';').map((part) => part.trim())
  const [nameValue, ...attributes] = parts
  const separator = nameValue.indexOf('=')
  assert(separator > 0, 'nonce Cookie 格式不正确')
  const name = nameValue.slice(0, separator)
  const value = nameValue.slice(separator + 1)
  const normalized = attributes.map((attribute) => attribute.toLowerCase())
  assert(name === nonceCookieName, 'nonce Cookie 名称不正确')
  assert(/^[A-Za-z0-9_-]{43}$/.test(value), 'nonce Cookie 必须是 256 位 base64url 值')
  assert(normalized.includes('secure'), 'nonce Cookie 缺少 Secure')
  assert(normalized.includes('httponly'), 'nonce Cookie 缺少 HttpOnly')
  assert(normalized.includes('samesite=lax'), 'nonce Cookie 必须为 SameSite=Lax')
  assert(normalized.includes('path=/'), 'nonce Cookie 必须为 Path=/')
  assert(normalized.includes('max-age=300'), 'nonce Cookie 必须在 5 分钟后过期')
  assert(
    !normalized.some((attribute) => attribute.startsWith('domain=')),
    'nonce Cookie 不得设置 Domain',
  )
  return { name, header: nameValue }
}

async function startAttempt(baseUrl, expected) {
  const authorizeUrl = new URL(authorizePath, baseUrl)
  const response = await safeFetch(
    authorizeUrl,
    { headers: { accept: 'text/html,application/xhtml+xml' } },
    '授权入口',
  )
  assert(response.status === 302, '授权入口必须返回 302')
  const location = response.headers.get('location')
  assert(location, '授权入口缺少 Location')
  let providerUrl
  try {
    providerUrl = new URL(location)
  } catch {
    fail('授权入口 Location 不是绝对 URL')
  }
  const state = providerUrl.searchParams.get('state')
  assert(providerUrl.protocol === 'https:', '企微授权地址必须使用 HTTPS')
  assert(providerUrl.hostname === 'open.weixin.qq.com', '企微授权 host 不正确')
  assert(!providerUrl.port, '企微授权地址不得使用自定义端口')
  assert(
    providerUrl.pathname === '/connect/oauth2/authorize',
    '企微授权路径不正确',
  )
  assert(providerUrl.hash === '#wechat_redirect', '企微授权 fragment 不正确')
  assert(!providerUrl.username && !providerUrl.password, '企微授权地址不得包含凭据')
  assert(
    [...providerUrl.searchParams.keys()].sort().join('\n') ===
      ['agentid', 'appid', 'redirect_uri', 'response_type', 'scope', 'state']
        .sort()
        .join('\n'),
    '企微授权参数白名单不正确',
  )
  assert(providerUrl.searchParams.get('appid') === expected.corpId, '授权 CorpID 不正确')
  assert(providerUrl.searchParams.get('agentid') === expected.agentId, '授权 AgentID 不正确')
  assert(
    providerUrl.searchParams.get('redirect_uri') === expected.callbackUrl.href,
    '授权 callback URI 不正确',
  )
  assert(providerUrl.searchParams.get('response_type') === 'code', '授权 response_type 不正确')
  assert(providerUrl.searchParams.get('scope') === 'snsapi_base', '授权 scope 不正确')
  assert(state && /^[A-Za-z0-9_-]{43}$/.test(state), '授权入口未生成 256 位 state')
  return { state, cookie: secureNonceCookie(response) }
}

async function expectAuthenticationFailure(callbackUrl, state, code, cookie, label) {
  const target = new URL(callbackUrl)
  target.searchParams.set('state', state)
  target.searchParams.set('code', code)
  const headers = { accept: 'application/json' }
  if (cookie) {
    headers.cookie = cookie
  }
  const response = await safeFetch(target, { headers }, label)
  assert(response.status === 401, `${label} 必须返回 401`)

  let body
  try {
    body = await response.json()
  } catch {
    fail(`${label} 未返回安全 JSON 错误`)
  }
  assert(
    body !== null && typeof body === 'object' && !Array.isArray(body),
    `${label} 错误响应形状不正确`,
  )
  assert(body.code === 'AUTHENTICATION_REQUIRED', `${label} 错误码不正确`)
  assert(body.retryable === false, `${label} retryable 不正确`)
  assert(Array.isArray(body.fieldErrors) && body.fieldErrors.length === 0, `${label} 含字段错误`)
  assert(
    body.details !== null &&
      typeof body.details === 'object' &&
      !Array.isArray(body.details) &&
      Object.keys(body.details).length === 0,
    `${label} 暴露了错误详情`,
  )
}

async function runNegativeChecks(baseUrl, callbackUrl, expected) {
  const forgedStateAttempt = await startAttempt(baseUrl, expected)
  const forgedState = randomBytes(32).toString('base64url')
  await expectAuthenticationFailure(
    callbackUrl,
    forgedState,
    invalidCode,
    forgedStateAttempt.cookie.header,
    '伪造 state',
  )

  const wrongNonceAttempt = await startAttempt(baseUrl, expected)
  const wrongNonce = randomBytes(32).toString('base64url')
  await expectAuthenticationFailure(
    callbackUrl,
    wrongNonceAttempt.state,
    invalidCode,
    `${wrongNonceAttempt.cookie.name}=${wrongNonce}`,
    '错误 nonce',
  )

  const invalidCodeAttempt = await startAttempt(baseUrl, expected)
  await expectAuthenticationFailure(
    callbackUrl,
    invalidCodeAttempt.state,
    invalidCode,
    invalidCodeAttempt.cookie.header,
    '无效 code',
  )
  await expectAuthenticationFailure(
    callbackUrl,
    invalidCodeAttempt.state,
    invalidCode,
    invalidCodeAttempt.cookie.header,
    '已消费 attempt 重放',
  )
}

async function collectReceipt(readline, number, hmacKey, runStartedAt) {
  process.stdout.write(
    `请完成第 ${number} 次真实授权，并粘贴单行 JSON 收据（输入不回显）：`,
  )
  let raw
  try {
    raw = await readline.question('')
  } finally {
    process.stdout.write('\n')
  }
  assert(raw.length > 0, `第 ${number} 份收据不能为空`)
  return validateReceipt(raw, hmacKey, `第 ${number} 份收据`, runStartedAt)
}

function passEvidence(firstReceipt, secondReceipt) {
  return {
    schemaVersion: 1,
    milestone: 'M0-12',
    status: 'PASS',
    verifiedAt: new Date(
      Math.max(
        Date.parse(firstReceipt.verifiedAt),
        Date.parse(secondReceipt.verifiedAt),
      ),
    ).toISOString(),
    corpFingerprint: firstReceipt.corpFingerprint,
    memberFingerprint: firstReceipt.memberFingerprint,
    receiptCount: 2,
    checks: {
      configurationPreflight: 'PASS',
      twoSignedReceipts: 'PASS',
      stableFingerprints: 'PASS',
      forgedStateRejected: 'PASS',
      wrongNonceRejected: 'PASS',
      invalidCodeRejected: 'PASS',
      replayRejected: 'PASS',
    },
  }
}

function writeEvidence(evidence) {
  const validate = evidenceValidator()
  assertValidEvidence(validate, evidence, '待写入证据')
  const temporaryPath = `${evidencePath}.tmp`
  fs.writeFileSync(temporaryPath, `${JSON.stringify(evidence, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
  })
  fs.renameSync(temporaryPath, evidencePath)
}

async function runLiveVerification() {
  const runStartedAt = Date.now()
  const { baseUrl, callbackUrl, hmacKey, corpId, agentId } =
    preflightConfiguration()
  console.log('配置预检通过；配置值和凭据不会输出。')
  await runNegativeChecks(baseUrl, callbackUrl, { callbackUrl, corpId, agentId })
  console.log('四项 401 负向检查通过。')
  console.log(`请在同一白名单成员的浏览器中两次访问 ${authorizePath}。`)

  const hiddenOutput = new Writable({
    write(_chunk, _encoding, callback) {
      callback()
    },
  })
  const readline = createInterface({
    input: process.stdin,
    output: hiddenOutput,
    terminal: Boolean(process.stdin.isTTY),
  })
  let first
  let second
  try {
    first = await collectReceipt(readline, 1, hmacKey, runStartedAt)
    second = await collectReceipt(readline, 2, hmacKey, runStartedAt)
  } finally {
    readline.close()
  }

  assert(first.requestId !== second.requestId, '两份收据必须来自两次不同请求')
  assert(
    first.corpFingerprint === second.corpFingerprint &&
      first.memberFingerprint === second.memberFingerprint,
    '两次授权的企业或成员指纹不一致',
  )
  writeEvidence(passEvidence(first, second))
  console.log('M0-12 真实验证 PASS；已写入脱敏 evidence/m0-12/live-verification.json。')
}

const args = process.argv.slice(2)
try {
  if (args.length === 1 && args[0] === '--validate-evidence') {
    validateEvidenceFiles()
  } else if (args.length === 0) {
    await runLiveVerification()
  } else {
    fail('仅支持无参数运行或 --validate-evidence')
  }
} catch (error) {
  const message = error instanceof Error ? error.message : '未知错误'
  console.error(message)
  process.exitCode = 1
}
