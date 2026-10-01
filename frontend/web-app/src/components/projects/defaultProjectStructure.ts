export const DEFAULT_PROJECT_STRUCTURE = {
  categories: [
    { code: 'REQUIREMENTS', name: '需求', color: 'BRIGHT_BLUE', sortOrder: 10, protected: true },
    { code: 'TASKS', name: '任务', color: 'BRIGHT_GREEN', sortOrder: 20, protected: true },
    { code: 'DEFECTS', name: '缺陷', color: 'DARK_RED', sortOrder: 30, protected: true },
  ],
  statuses: [
    { code: 'NOT_STARTED', name: '未开始', color: 'GRAY', category: 'TODO', sortOrder: 0, protected: true },
    { code: 'IN_PROGRESS', name: '进行中', color: 'ORANGE', category: 'IN_PROGRESS', sortOrder: 10, protected: false },
    { code: 'STUCK', name: '卡住', color: 'RED', category: 'IN_PROGRESS', sortOrder: 20, protected: false },
    { code: 'DONE', name: '已完成', color: 'GREEN', category: 'DONE', sortOrder: 30, protected: false },
    { code: 'CANCELED', name: '已取消', color: 'AMERICAN_GRAY', category: 'CANCELED', sortOrder: 40, protected: false },
  ],
  priorities: [
    { code: 'LOW', name: '低', color: 'BLUE', sortOrder: 10 },
    { code: 'MEDIUM', name: '中', color: 'TEAL', sortOrder: 20 },
    { code: 'HIGH', name: '高', color: 'ORANGE', sortOrder: 30 },
    { code: 'URGENT', name: '紧急', color: 'RED', sortOrder: 40 },
  ],
} as const
