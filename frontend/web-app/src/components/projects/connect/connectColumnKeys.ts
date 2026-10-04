import type { ConnectColumn, ConnectColumnCatalog, ConnectColumnIncoming, ProjectWorkItemListItem } from '@yumpoo/api-client'

export type ConnectColumnKey = `connect:${string}` | `connect-reverse:${string}` | 'connect-draft'
export type ConnectSourceItem = Pick<ProjectWorkItemListItem, 'id' | 'title'>
/** A connect column owned by this project, the reverse side of another project's column, or the unsaved column being set up. */
export interface ConnectTableColumn {
  key: ConnectColumnKey
  label: string
  kind: 'connect' | 'reverse' | 'draft'
  width: number
  minWidth: number
  column?: ConnectColumn | undefined
  reverse?: ConnectColumnIncoming | undefined
}

export const CONNECT_DRAFT_KEY = 'connect-draft' as const
export const CONNECT_COLUMN_NAME_LIMIT = 40
export function connectColumnKey(id: string): ConnectColumnKey { return `connect:${id}` }
export function reverseColumnKey(id: string): ConnectColumnKey { return `connect-reverse:${id}` }
export function isConnectColumnKey(key: string): key is ConnectColumnKey {
  return key === CONNECT_DRAFT_KEY || /^connect(?:-reverse)?:[^\s:]+$/u.test(key)
}
/** Keys that carry width and visibility preferences; the draft column never does. */
export function connectColumnKeys(catalog?: ConnectColumnCatalog): ConnectColumnKey[] {
  return catalog ? [...catalog.items.map(column => connectColumnKey(column.id)),
    ...catalog.incomingColumns.map(column => reverseColumnKey(column.columnId))] : []
}
export function connectColumnDefaultWidth(_key: ConnectColumnKey): number { return 200 }
export function connectColumnMinWidth(_key: ConnectColumnKey): number { return 140 }

/** Reverse columns are named after the source project; columns from the same project fall back to "项目 · 列名". */
export function reverseColumnLabels(columns: ConnectColumnIncoming[]): Map<string, string> {
  const counts = new Map<string, number>()
  columns.forEach(column => counts.set(column.projectId, (counts.get(column.projectId) ?? 0) + 1))
  return new Map(columns.map(column => [column.columnId,
    (counts.get(column.projectId) ?? 0) > 1 ? `${column.projectName} · ${column.columnName}` : column.projectName]))
}

export const builtInColumnNames = ['工作项名称', '处理人', '状态', '优先级', '工作项类别', '截止日期', '时长追踪', '最后更新时间', '被连接']

const characters = (value: string) => Array.from(value)
const truncate = (value: string, length: number) => characters(value).slice(0, Math.max(1, length)).join('')
function taken(names: string[]): Set<string> {
  return new Set([...builtInColumnNames, ...names].map(name => name.trim().toLocaleLowerCase()))
}

/** Names a column after the projects it connects, keeping within the server's 40-character, unique-name rules. */
export function connectColumnAutoName(projectNames: string[], existingNames: string[]): string {
  const names = projectNames.map(name => name.trim()).filter(Boolean)
  const joined = names.join('、')
  let base = '连接项目'
  if (names.length && characters(joined).length <= CONNECT_COLUMN_NAME_LIMIT) base = joined
  else if (names.length) {
    const suffix = ` 等 ${names.length} 个项目`
    base = truncate(names[0]!, CONNECT_COLUMN_NAME_LIMIT - characters(suffix).length) + suffix
  }
  const used = taken(existingNames)
  if (!used.has(base.toLocaleLowerCase())) return base
  for (let index = 2; ; index++) {
    const suffix = ` ${index}`
    const candidate = truncate(base, CONNECT_COLUMN_NAME_LIMIT - suffix.length) + suffix
    if (!used.has(candidate.toLocaleLowerCase())) return candidate
  }
}

export function connectColumnNameError(name: string, existingNames: string[]): string {
  const value = name.trim()
  if (!value) return '请输入列名称'
  if (characters(value).length > CONNECT_COLUMN_NAME_LIMIT) return '列名称最多 40 个字符'
  return taken(existingNames).has(value.toLocaleLowerCase()) ? '列名称已存在，请换一个名称' : ''
}
