import fs from 'node:fs'
import path from 'node:path'
import {
  assertDelivery,
  atomicWriteJson,
  commitPattern,
  gitHead,
  readJson,
  sha256File,
  sha256Pattern,
} from './delivery-utils.mjs'

const expectedKeys = [
  'architecture',
  'check',
  'completedAt',
  'milestone',
  'platform',
  'schemaVersion',
  'sourceCommit',
  'status',
  'testedJarSha256',
]

export function writeServerSmokeReceipt(repositoryRoot, jarPath, receiptPath) {
  const target = resolveServerSmokeReceiptPath(repositoryRoot, receiptPath)
  const receipt = {
    schemaVersion: 1,
    milestone: 'M0-18',
    check: 'M0-16_PACKAGED_JAR_SMOKE',
    status: 'PASS',
    sourceCommit: gitHead(repositoryRoot),
    testedJarSha256: sha256File(jarPath),
    platform: process.platform,
    architecture: process.arch,
    completedAt: new Date().toISOString(),
  }
  validateReceipt(receipt)
  atomicWriteJson(target, receipt)
  return receipt
}

export function verifyServerSmokeReceipt(repositoryRoot, receiptPath, options) {
  assertDelivery(typeof receiptPath === 'string' && receiptPath.length > 0, 'server smoke receipt 缺失')
  const target = options.allowExternalPathForTest
    ? path.resolve(receiptPath)
    : resolveServerSmokeReceiptPath(repositoryRoot, receiptPath)
  const metadata = fs.lstatSync(target, { throwIfNoEntry: false })
  assertDelivery(metadata?.isFile() && !metadata.isSymbolicLink(), 'server smoke receipt 缺失或不是普通文件')
  const receipt = readJson(target, 'server smoke receipt')
  validateReceipt(receipt)
  assertDelivery(receipt.sourceCommit === options.expectedCommit, 'server smoke receipt 未绑定当前提交')
  assertDelivery(receipt.testedJarSha256 === options.expectedJarSha256, 'server smoke receipt 未绑定已测试 JAR')
  assertDelivery(Date.parse(receipt.completedAt) >= Date.parse(options.notBefore), 'server smoke receipt 早于 portable handoff')
  assertDelivery(Date.parse(receipt.completedAt) <= Date.now() + 120_000, 'server smoke receipt 完成时间位于未来')
  if (options.consume) fs.rmSync(target)
  return receipt
}

export function resolveServerSmokeReceiptPath(repositoryRoot, receiptPath) {
  assertDelivery(typeof receiptPath === 'string' && receiptPath.length > 0, 'server smoke receipt 路径缺失')
  const ownedRoot = path.resolve(repositoryRoot, 'out', 'm0-18')
  const target = path.resolve(receiptPath)
  assertDelivery(target.startsWith(`${ownedRoot}${path.sep}`), 'server smoke receipt 必须位于 out/m0-18')
  return target
}

function validateReceipt(receipt) {
  assertDelivery(receipt && typeof receipt === 'object' && !Array.isArray(receipt), 'server smoke receipt 格式无效')
  assertDelivery(
    JSON.stringify(Object.keys(receipt).sort()) === JSON.stringify(expectedKeys),
    'server smoke receipt 字段集无效',
  )
  assertDelivery(receipt.schemaVersion === 1 && receipt.milestone === 'M0-18', 'server smoke receipt 版本无效')
  assertDelivery(receipt.check === 'M0-16_PACKAGED_JAR_SMOKE' && receipt.status === 'PASS', 'server smoke receipt 状态无效')
  assertDelivery(commitPattern.test(receipt.sourceCommit), 'server smoke receipt 提交无效')
  assertDelivery(sha256Pattern.test(receipt.testedJarSha256), 'server smoke receipt JAR 摘要无效')
  assertDelivery(receipt.platform === 'win32' && receipt.architecture === 'x64', 'server smoke receipt 必须来自 Windows x64')
  assertDelivery(Number.isFinite(Date.parse(receipt.completedAt)), 'server smoke receipt 时间无效')
}
