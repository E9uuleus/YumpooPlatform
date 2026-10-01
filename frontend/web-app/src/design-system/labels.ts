const labels: Record<string, string> = {
  OWNER: '负责人',
  MEMBER: '成员',
  COMPANY_MEMBER: '普通员工',
  COMPANY_ADMIN: '公司管理员',
  APP_MANAGER: '平台管理员',
  MANUAL: '手动',
  SCHEDULED: '计划任务',
  COLLECTING_IDS: '收集成员标识',
  COLLECTING_PROFILES: '收集成员资料',
  APPLYING: '应用变更',
  FINALIZING: '收尾',
  COMPLETED: '已完成',
  DEVELOPMENT: '研发',
  DELIVERY: '交付',
  SUPPORT: '支持',
  USED_BY: '使用方',
}

export function businessLabel(value: string | null | undefined): string {
  if (!value) return '—'
  return labels[value] ?? `未知（${value}）`
}
