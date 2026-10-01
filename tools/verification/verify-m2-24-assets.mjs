import { verifyHistoricalMilestone } from '../ci/historical-assets.mjs'
verifyHistoricalMilestone('M2-24')
console.log('M2-24 仅校验历史验收记录；当前行为请运行 pnpm ci:static 与 pnpm ci:backend。')
