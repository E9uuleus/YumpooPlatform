const labels: Record<string, string> = {
  PRODUCT_DEVELOPMENT: '产品研发',
  PRE_SALES: '售前',
  IMPLEMENTATION: '实施',
  HYPERCARE: '运维保障',
  OWNER: '负责人',
  MEMBER: '成员',
  COMPANY_MEMBER: '企业成员',
  COMPANY_ADMIN: '企业管理员',
  APP_MANAGER: '平台管理员',
  MANUAL: '手动',
  SCHEDULED: '计划任务',
  COLLECTING_IDS: '收集成员标识',
  COLLECTING_PROFILES: '收集成员资料',
  APPLYING: '应用变更',
  FINALIZING: '收尾',
  COMPLETED: '已完成',
}

export function businessLabel(value: string | null | undefined): string {
  if (!value) return '—'
  return labels[value] ?? `未知（${value}）`
}
