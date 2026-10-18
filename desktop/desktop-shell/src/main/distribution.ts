import { readFileSync } from 'node:fs'
import path from 'node:path'

/** Build-time settings baked into the packaged app; the installed shell never reads YUMPOO_WEB_URL. */
export interface DesktopDistribution {
  readonly schemaVersion: 1
  readonly origin: string
}

export const DISTRIBUTION_FILE = 'distribution.json'
const DISTRIBUTION_FIELDS = ['origin', 'schemaVersion']

export function parseDistribution(value: unknown): DesktopDistribution {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('分发配置必须是 JSON object')
  }
  const record = value as Record<string, unknown>
  if (Object.keys(record).sort().join() !== DISTRIBUTION_FIELDS.join()) {
    throw new Error('分发配置字段白名单不正确')
  }
  if (record.schemaVersion !== 1) {
    throw new Error('分发配置 schemaVersion 不受支持')
  }
  if (typeof record.origin !== 'string') {
    throw new Error('分发配置 origin 必须是字符串')
  }
  let origin: URL
  try {
    origin = new URL(record.origin)
  } catch {
    throw new Error('分发配置 origin 不是有效 URL')
  }
  if (origin.protocol !== 'https:' || origin.origin !== record.origin) {
    throw new Error('分发配置 origin 必须是不含路径、查询和凭据的 HTTPS origin')
  }
  return Object.freeze({ schemaVersion: 1, origin: origin.origin })
}

export function loadDistribution(directory: string = __dirname): DesktopDistribution {
  const file = path.join(directory, DISTRIBUTION_FILE)
  let content: string
  try {
    content = readFileSync(file, 'utf8')
  } catch {
    throw new Error('打包桌面壳缺少分发配置')
  }
  return parseDistribution(JSON.parse(content))
}
