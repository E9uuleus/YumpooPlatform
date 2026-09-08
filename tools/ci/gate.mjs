import { fileURLToPath } from 'node:url'
import path from 'node:path'

export const requiredJobs = ['linux', 'windows_delivery']

export function assertRequiredJobs(needs, required = requiredJobs) {
  if (!needs || typeof needs !== 'object' || Array.isArray(needs)
    || Object.keys(needs).sort().join(',') !== [...required].sort().join(',')) {
    throw new Error('合并门禁的依赖集合缺失或发生未登记变更')
  }
  for (const job of required) {
    if (needs[job]?.result !== 'success' || needs[job]?.outputs?.completed !== 'true') {
      throw new Error(`必需任务 ${job} 未完整成功：${needs[job]?.result ?? 'missing'}`)
    }
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    assertRequiredJobs(JSON.parse(process.env.NEEDS_JSON))
    console.log('所有必需任务及完成凭据均已通过。')
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
