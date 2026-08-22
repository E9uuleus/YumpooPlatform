export type StatusDomain =
  | 'project-lifecycle'
  | 'project-membership'
  | 'product-status'
  | 'content-status'
  | 'account'
  | 'employment'
  | 'directory-sync'
  | 'integration'

export type StatusTone = 'blue' | 'green' | 'yellow' | 'red' | 'purple' | 'teal' | 'pink' | 'gray'

export interface StatusPresentation {
  label: string
  tone: StatusTone
}

const statusMappings: Record<StatusDomain, Record<string, StatusPresentation>> = {
  'project-lifecycle': {
    DRAFT: { label: '草稿', tone: 'gray' },
    ACTIVE: { label: '活跃', tone: 'blue' },
    ARCHIVED: { label: '已归档', tone: 'gray' },
  },
  'project-membership': {
    ACTIVE: { label: '活跃', tone: 'blue' },
    REMOVED: { label: '已移除', tone: 'gray' },
  },
  'product-status': {
    ACTIVE: { label: '活跃', tone: 'green' },
    ARCHIVED: { label: '已归档', tone: 'gray' },
  },
  'content-status': {
    ACTIVE: { label: '使用中', tone: 'green' },
    ARCHIVED: { label: '已归档', tone: 'gray' },
  },
  account: {
    ENABLED: { label: '已启用', tone: 'green' },
    DISABLED: { label: '已停用', tone: 'red' },
  },
  employment: {
    ACTIVE: { label: '在职', tone: 'green' },
    LEFT: { label: '已离职', tone: 'gray' },
  },
  'directory-sync': {
    RUNNING: { label: '运行中', tone: 'blue' },
    PARTIALLY_SUCCEEDED: { label: '部分成功', tone: 'yellow' },
    SUCCEEDED: { label: '成功', tone: 'green' },
    FAILED: { label: '失败', tone: 'red' },
  },
  integration: {
    ENABLED: { label: '已启用', tone: 'green' },
    DISABLED: { label: '未启用', tone: 'gray' },
    CONFIGURED: { label: '配置完整', tone: 'green' },
    INCOMPLETE: { label: '配置不完整', tone: 'yellow' },
  },
}

export function getStatusPresentation(domain: StatusDomain, status: string): StatusPresentation {
  return statusMappings[domain][status] ?? {
    label: `未知（${status}）`,
    tone: 'gray',
  }
}
