import { verifyHistoricalMilestone } from '../ci/historical-assets.mjs'
verifyHistoricalMilestone('M2-01')
console.log('M2-01 仅校验历史验收记录；当前业务验证请运行 pnpm run ci:static 与 pnpm run ci:backend。')
