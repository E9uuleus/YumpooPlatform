import { DataLine, Monitor, OfficeBuilding, Setting } from '@element-plus/icons-vue'

/** Module tile, label and colour shared by the module page headers; icons match the module rail. */
export const MODULES = {
  team: { label: '团队', icon: DataLine, color: 'var(--yp-status-purple)' },
  company: { label: '公司管理', icon: OfficeBuilding, color: 'var(--yp-status-blue)' },
  operations: { label: '运维中心', icon: Monitor, color: 'var(--yp-status-teal)' },
  settings: { label: '个人', icon: Setting, color: 'var(--yp-status-green)' },
} as const

export type ModuleKey = keyof typeof MODULES
