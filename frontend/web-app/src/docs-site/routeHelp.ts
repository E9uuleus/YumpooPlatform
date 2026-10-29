/** 应用路由名 → 帮助页 slug 或 slug#anchor；顶栏帮助菜单的「本页帮助」据此打开。只做重定向的入口路由不需要映射。 */
export const ROUTE_HELP: Record<string, string> = {
  home: 'guide/navigation',
  settings: 'guide/navigation#偏好设置',
  workspace: 'guide/projects#管理项目',
  'archived-projects': 'guide/projects#归档与恢复',
  'project-overview': 'guide/work-items',
  'project-statistics': 'guide/projects#项目统计',
  'project-members': 'guide/projects#成员与负责人',
  'project-activity': 'guide/projects#动态',
  'project-settings': 'guide/projects#项目设置',
  dashboards: 'guide/dashboards',
  'my-time': 'guide/time-tracking#我的工时',
  'my-time-day': 'guide/time-tracking#我的一天',
  team: 'guide/team',
  'team-hours-import': 'guide/team#迁入工时',
  'company-overview': 'guide/company#概览',
  'company-projects': 'guide/company#项目总览',
  'company-members': 'guide/company#成员与角色',
  'company-activity': 'guide/company#活动日志',
  'company-sync-runs': 'guide/company#同步运行',
  'company-scheduled-jobs': 'guide/company#定时任务',
  'company-work-calendar': 'guide/company#工作日历',
  'operations-overview': 'guide/operations#概览',
  'operations-host': 'guide/operations#主机与运行时',
  'operations-sessions': 'guide/operations#在线会话',
  'operations-logs': 'guide/operations#运行日志',
  'operations-alerts': 'guide/operations#告警',
  inbox: 'guide/inbox',
}

export function routeHelpPath(routeName: unknown): string | undefined {
  const target = typeof routeName === 'string' ? ROUTE_HELP[routeName] : undefined
  if (!target) return undefined
  const [slug, anchor] = target.split('#')
  return `/docs/${slug}${anchor ? `#${encodeURIComponent(anchor)}` : ''}`
}
