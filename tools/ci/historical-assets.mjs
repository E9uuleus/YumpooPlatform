import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { verifyHistoricalMilestone as verifyHistoricalReport } from '../verification/content-category-refactor-assets.mjs'

export const historicalMilestones = [
  'M2-01', 'M2-03', 'M2-04', 'M2-06', 'M2-07', 'M2-09', 'M2-10', 'M2-11', 'M2-12', 'M2-13', 'M2-14', 'M2-15', 'M2-16', 'M2-19A', 'M2-24',
]

const reportPolicies = {
  'M2-01': { acceptanceField: 'verifiedRequirements' },
  'M2-24': {
    status: 'VERIFIED',
    validateReport(report) {
      if (!(report.schemaVersion === 1 && report.flywayVersion === '45'
        && report.checks?.fullMavenGate === true && report.checks?.linuxPrCi === true
        && report.checks?.assetGate === true && Object.values(report.checks).every(value => value === true))) {
        throw new Error('M2-24 历史检查未完整成功')
      }
    },
  },
}

export function verifyHistoricalMilestone(milestone, root) {
  verifyHistoricalReport(milestone, root, reportPolicies[milestone])
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  for (const milestone of historicalMilestones) verifyHistoricalMilestone(milestone)
  console.log('历史验收记录结构有效；当前业务行为由全量回归与当前契约检查负责。')
}
