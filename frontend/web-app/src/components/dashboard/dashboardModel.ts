import type { DashboardBucket, DashboardConfiguration, DashboardConnection, DashboardFilters, DashboardWidget } from '@yumpoo/api-client'

export const widgetCatalog = [
  { kind: 'METRIC', title: '指标卡', description: '用一个关键数字掌握进展', icon: 'number' },
  { kind: 'STATUS', title: '状态分布', description: '工作项数量、占比与状态一目了然', icon: 'donut' },
  { kind: 'PROJECT_WORKLOAD', title: '项目工作量', description: '比较各项目的工作量与状态组成', icon: 'stack' },
  { kind: 'MEMBER_WORKLOAD', title: '成员工作量', description: '了解每位处理人的任务分配', icon: 'bars' },
  { kind: 'PROJECT_TIME', title: '项目耗时', description: '汇总各项目实际投入的计时时长', icon: 'time' },
] as const
export const metrics = { TOTAL: '工作项总数', IN_PROGRESS: '进行中', DONE: '已完成', COMPLETION_RATE: '完成率', DURATION: '累计耗时' }
export const categories: Record<string, { name: string; color: string }> = {
  TODO: { name: '待开始', color: 'GRAY' }, IN_PROGRESS: { name: '进行中', color: 'ORANGE' },
  DONE: { name: '已完成', color: 'GREEN' }, CANCELED: { name: '已取消', color: 'RED' },
}
export function emptyFilters(): DashboardFilters {
  return { projectIds: [], assignees: [], statuses: [], priorities: [], contentIds: [], categories: [], dueFrom: null, dueTo: null, includeArchived: false, query: '', hasTime: false }
}
export function clone<T>(value: T): T { return structuredClone(JSON.parse(JSON.stringify(value))) as T }
export function newWidget(kind: `${DashboardWidget['kind']}`, existing: DashboardWidget[] = []): DashboardWidget {
  const metric = kind === 'METRIC'
  return { id: crypto.randomUUID(), kind: kind as DashboardWidget['kind'], title: widgetCatalog.find(c => c.kind === kind)!.title,
    metric: 'TOTAL' as DashboardWidget['metric'], grouping: 'STATUS' as DashboardWidget['grouping'], sort: 'DESC' as DashboardWidget['sort'], showLegend: true, showValues: true,
    wide: { x: 0, y: Math.max(0, ...existing.map(w => w.wide.y + w.wide.h)), w: metric ? 3 : 6, h: metric ? 4 : 9 },
    medium: { x: 0, y: Math.max(0, ...existing.map(w => w.medium.y + w.medium.h)), w: metric ? 3 : 6, h: metric ? 4 : 9 } }
}
export function defaultConfiguration(blank = false): DashboardConfiguration {
  const widgets: DashboardWidget[] = []
  if (!blank) {
    ;(['TOTAL', 'IN_PROGRESS', 'DONE', 'DURATION'] as const).forEach((metric, i) => {
      const widget = newWidget('METRIC'); widget.metric = metric as DashboardWidget['metric']; widget.title = metrics[metric]
      widget.wide = { x: i * 3, y: 0, w: 3, h: 4 }; widget.medium = { x: (i % 2) * 3, y: Math.floor(i / 2) * 4, w: 3, h: 4 }
      widgets.push(widget)
    })
    ;(['STATUS', 'PROJECT_WORKLOAD', 'MEMBER_WORKLOAD', 'PROJECT_TIME'] as const).forEach((kind, i) => {
      const widget = newWidget(kind)
      widget.wide = { x: i % 2 === 0 ? 0 : i === 1 ? 5 : 6, y: 4 + Math.floor(i / 2) * 9, w: i === 0 ? 5 : i === 1 ? 7 : 6, h: 9 }
      widget.medium = { x: 0, y: 8 + i * 9, w: 6, h: 9 }; widgets.push(widget)
    })
  }
  return { projectIds: [], widgets, filters: emptyFilters() }
}
export function duration(ms: number): string {
  const minutes = Math.floor(Math.max(0, ms) / 60000)
  return minutes ? `${Math.floor(minutes / 60)}h ${minutes % 60}m` : ms > 0 ? '< 1m' : '0h'
}
export interface StatusGroup { key: string; name: string; color: string; count: number; keys: string[]; category: string }
export function statusGroups(buckets: DashboardBucket[], projects: DashboardConnection[], grouping: string): StatusGroup[] {
  const result = new Map<string, StatusGroup>()
  const statuses = buckets.filter(b => b.kind === 'STATUS')
  for (const bucket of statuses) {
    const category = bucket.category || 'TODO'
    const key = grouping === 'CATEGORY' ? category : JSON.stringify([bucket.code, category, bucket.label, bucket.colorToken])
    let group = result.get(key)
    if (!group) {
      const ambiguous = statuses.some(b => b.label === bucket.label && (b.code !== bucket.code || b.category !== category || b.colorToken !== bucket.colorToken))
      group = { key, category, name: grouping === 'CATEGORY' ? categories[category]?.name || category : `${bucket.label || bucket.code}${ambiguous ? ` · ${projects.find(p => p.id === bucket.projectId)?.name || '项目'}` : ''}`, color: grouping === 'CATEGORY' ? categories[category]?.color || 'GRAY' : bucket.colorToken || 'GRAY', count: 0, keys: [] }
      result.set(key, group)
    }
    group.count += bucket.count; group.keys.push(bucket.key)
  }
  return [...result.values()]
}
export function drillFilters(base: DashboardFilters, widget: DashboardWidget, selection?: { kind: string; keys: string[] }): DashboardFilters {
  const next = clone(base)
  if (selection?.kind === 'STATUS') next.statuses = selection.keys
  else if (selection?.kind === 'MEMBER') next.assignees = selection.keys
  else if (selection?.kind === 'PROJECT') next.projectIds = selection.keys
  if (widget.kind === 'PROJECT_TIME' || widget.metric === 'DURATION' && widget.kind === 'METRIC') next.hasTime = true
  if (widget.kind === 'METRIC' && ['IN_PROGRESS', 'DONE', 'COMPLETION_RATE'].includes(widget.metric)) next.categories = [widget.metric === 'IN_PROGRESS' ? 'IN_PROGRESS' : 'DONE']
  return next
}
