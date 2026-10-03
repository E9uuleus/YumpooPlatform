import type { ConnectColumn, ConnectColumnCatalog, ProjectWorkItemListItem } from '@yumpoo/api-client'

export type ConnectColumnKey = `connect:${string}` | 'connect-incoming'
export type ConnectSourceItem = Pick<ProjectWorkItemListItem, 'id' | 'title'>
export interface ConnectTableColumn {
  key: ConnectColumnKey
  label: string
  kind: 'connect' | 'incoming'
  width: number
  minWidth: number
  column?: ConnectColumn | undefined
}

export function connectColumnKey(id: string): ConnectColumnKey { return `connect:${id}` }
export function isConnectColumnKey(key: string): key is ConnectColumnKey {
  return key === 'connect-incoming' || /^connect:[^\s:]+$/u.test(key)
}
export function connectColumnKeys(catalog?: ConnectColumnCatalog): ConnectColumnKey[] {
  return catalog ? [...catalog.items.map(column => connectColumnKey(column.id)),
    ...(catalog.incomingAvailable ? ['connect-incoming' as const] : [])] : []
}
export function connectColumnDefaultWidth(key: ConnectColumnKey): number { return key === 'connect-incoming' ? 220 : 200 }
export function connectColumnMinWidth(key: ConnectColumnKey): number { return key === 'connect-incoming' ? 160 : 140 }

export const builtInColumnNames = ['工作项名称', '处理人', '状态', '优先级', '工作项类别', '截止日期', '时长追踪', '最后更新时间', '被连接']
export function suggestedConnectColumnName(names: string[]): string {
  const used = new Set(names.map(name => name.trim().toLocaleLowerCase()))
  let name = '连接项目'
  for (let suffix = 2; used.has(name.toLocaleLowerCase()); suffix++) name = `连接项目 ${suffix}`
  return name
}
