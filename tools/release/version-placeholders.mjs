import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// 产品版本只由 Release tag 决定；仓库内版本字段固定为占位值，避免被误读或手工递增。
// desktop-shell 拥有独立的桌面版本线，不在此列。
export const packagePlaceholders = {
  'package.json': '0.0.0',
  'packages/api-client/package.json': '0.0.0',
  'packages/preload-contract/package.json': '0.0.0',
  'frontend/web-app/package.json': '0.0.0',
}
// 备份清单与历史证据已记录该值，因此保留原占位符而不改为 0.0.0。
export const pomPlaceholder = '0.0.1-SNAPSHOT'

export function versionPlaceholderViolations(root) {
  const violations = []
  for (const [file, expected] of Object.entries(packagePlaceholders)) {
    const actual = JSON.parse(fs.readFileSync(path.join(root, file), 'utf8')).version
    if (actual !== expected) violations.push(`${file} version=${actual}，应保持占位值 ${expected}`)
  }
  const pom = fs.readFileSync(path.join(root, 'backend', 'pom.xml'), 'utf8').replace(/<parent>[\s\S]*?<\/parent>/u, '')
  const actual = pom.match(/<artifactId>yumpoo-server<\/artifactId>\s*<version>([^<]*)<\/version>/u)?.[1]
  if (actual !== pomPlaceholder) violations.push(`backend/pom.xml version=${actual}，应保持占位值 ${pomPlaceholder}`)
  return violations
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const violations = versionPlaceholderViolations(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..'))
  if (violations.length) {
    console.error(`仓库版本占位符被修改；产品版本以 Release tag 为准：\n${violations.join('\n')}`)
    process.exitCode = 1
  } else {
    console.log('仓库版本字段保持占位值；产品版本以 Release tag 为准')
  }
}
