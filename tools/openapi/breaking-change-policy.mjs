import fs from 'node:fs'
import path from 'node:path'

export function readBreakingChangeExceptions(root) {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'tools/openapi/breaking-change-exceptions.json'), 'utf8'))
  if (manifest.schemaVersion !== 1 || !Array.isArray(manifest.exceptions)) {
    throw new Error('OpenAPI 破坏变更清单结构无效')
  }
  const ids = new Set()
  const pairs = new Set()
  for (const entry of manifest.exceptions) {
    const pair = `${entry.oldSha256}:${entry.newSha256}`
    if (typeof entry.id !== 'string' || !entry.id.trim() || ids.has(entry.id) || pairs.has(pair)
      || !/^[a-f0-9]{64}$/u.test(entry.oldSha256) || !/^[a-f0-9]{64}$/u.test(entry.newSha256)
      || entry.oldSha256 === entry.newSha256 || typeof entry.reason !== 'string' || !entry.reason.trim()
      || !/^\.agents\/notes\/implemented\/[a-z]+\/\d{4}-\d{2}-\d{2}-[a-z0-9-]+\.md$/u.test(entry.agentNote)) {
      throw new Error(`OpenAPI 破坏变更条目无效或重复：${entry.id}`)
    }
    const note = fs.readFileSync(path.join(root, entry.agentNote), 'utf8')
    if (!/^Status: implemented$/mu.test(note)) throw new Error(`OpenAPI 例外缺少活动实施决定：${entry.id}`)
    ids.add(entry.id)
    pairs.add(pair)
  }
  return manifest.exceptions
}

export function exactBreakingChangeException(exceptions, oldSha256, newSha256) {
  return exceptions.find(entry => entry.oldSha256 === oldSha256 && entry.newSha256 === newSha256)
}
