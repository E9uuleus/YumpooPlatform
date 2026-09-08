import { verifyHistoricalMilestone } from '../verification/content-category-refactor-assets.mjs'

for (const milestone of ['M2-09', 'M2-10', 'M2-11', 'M2-12', 'M2-13', 'M2-14', 'M2-15', 'M2-16', 'M2-19A']) {
  verifyHistoricalMilestone(milestone)
}
console.log('历史验收记录结构有效；当前业务行为由全量回归与当前契约检查负责。')
