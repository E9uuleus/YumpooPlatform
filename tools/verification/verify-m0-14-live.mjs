import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'
import { runSync } from './process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const backend = path.join(root, 'backend')

function validateHistoricalEvidence() {
  const evidenceRoot = path.join(root, 'evidence', 'm0-14')
  const read = file => JSON.parse(fs.readFileSync(path.join(evidenceRoot, file), 'utf8'))
  const ajv = new Ajv({ allErrors: true, strict: false })
  addFormats(ajv)
  const validate = ajv.compile(read('live-verification.schema.json'))
  const example = read('live-verification.example.json')
  const evidence = read('live-verification.json')
  if (!validate(example) || !validate(evidence) || example.status !== 'NOT_RUN') {
    throw new Error('M0-14 历史证据不符合既有 Schema')
  }
  console.log('M0-14 历史证据有效；扫描能力已移除，旧证据保持冻结。')
}

if (process.argv.includes('--validate-evidence')) {
  validateHistoricalEvidence()
} else {
  if (process.platform !== 'win32' || process.env.YUMPOO_M014_LIVE_ENABLED !== 'true') {
    throw new Error('M0-14 存储 live 验证需要 Windows 与 YUMPOO_M014_LIVE_ENABLED=true')
  }
  const liveRoot = process.env.YUMPOO_M014_LIVE_ROOT
  if (!liveRoot || !fs.statSync(liveRoot, { throwIfNoEntry: false })?.isDirectory()
      || fs.lstatSync(liveRoot).isSymbolicLink()) {
    throw new Error('YUMPOO_M014_LIVE_ROOT 必须指向已存在的真实目标目录')
  }
  runSync('cmd.exe', ['/d', '/s', '/c', 'mvnw.cmd -q -Dtest=M014FileStorageLiveVerification,LocalFileQuarantineStorageTest test'], { cwd: backend })
  console.log('M0-14 NTFS、同卷原子发布、固定缓冲、容量上限与断流清理验证通过。')
}
