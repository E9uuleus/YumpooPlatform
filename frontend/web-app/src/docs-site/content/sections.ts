export const SECTIONS = [
  { id: 'intro', label: '介绍' },
  { id: 'start', label: '快速开始' },
  { id: 'guide', label: '使用指南' },
  { id: 'architecture', label: '架构' },
] as const

export type SectionId = typeof SECTIONS[number]['id']
