import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { verifyHistoricalMilestone } from '../verification/content-category-refactor-assets.mjs'

export const historicalMilestones = [
  'M2-03', 'M2-07', 'M2-09', 'M2-10', 'M2-11', 'M2-12', 'M2-13', 'M2-14', 'M2-15', 'M2-16', 'M2-19A', 'M2-24',
]

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  for (const milestone of historicalMilestones) verifyHistoricalMilestone(milestone)
  console.log('历史验收记录结构有效；当前业务行为由全量回归与当前契约检查负责。')
}
