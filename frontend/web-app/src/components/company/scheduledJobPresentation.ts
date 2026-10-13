import type { RouteLocationRaw } from 'vue-router'

export interface ScheduleDraft { kind: 'daily' | 'interval'; time: string; hours: number }

export const INTERVAL_HOURS = [1, 2, 3, 4, 6, 8, 12] as const
export const DEFAULT_SCHEDULE: ScheduleDraft = { kind: 'daily', time: '02:00', hours: 6 }

const DAILY = /^0 (\d{1,2}) (\d{1,2}) \* \* \*$/
const INTERVAL = /^0 0 \*\/(\d{1,2}) \* \* \*$/

/** The page only writes these two Spring cron shapes; anything else is shown verbatim. */
export function toCron(draft: ScheduleDraft): string {
  if (draft.kind === 'interval') return `0 0 */${draft.hours} * * *`
  const [hour, minute] = draft.time.split(':').map(Number)
  return `0 ${minute} ${hour} * * *`
}

export function parseCron(cron: string): ScheduleDraft | undefined {
  const daily = DAILY.exec(cron)
  if (daily) return { ...DEFAULT_SCHEDULE, kind: 'daily', time: `${daily[2]!.padStart(2, '0')}:${daily[1]!.padStart(2, '0')}` }
  const interval = INTERVAL.exec(cron)
  if (interval) return { ...DEFAULT_SCHEDULE, kind: 'interval', hours: Number(interval[1]) }
  return undefined
}

export function describeCron(cron: string): string {
  const draft = parseCron(cron)
  if (!draft) return cron
  return draft.kind === 'daily' ? `每天 ${draft.time}` : `每 ${draft.hours} 小时`
}

export interface ScheduledJobMeta { label: string; description: string; owner: string; history?: RouteLocationRaw }

const JOBS: Record<string, ScheduledJobMeta> = {
  WORK_HOURS_ANOMALY: {
    label: '工时异常检查',
    description: '每天检查前一自然日成员总工时，超过阈值时向公司管理员发送站内提醒。',
    owner: '报表',
  },
  DIRECTORY_SYNC: {
    label: '定时同步企微通讯录',
    description: '全量同步企微成员与在职状态，离职成员自动停用账号。',
    owner: '身份与权限',
    history: { name: 'company-sync-runs', query: { triggerType: 'SCHEDULED' } },
  },
}

export function scheduledJobMeta(code: string): ScheduledJobMeta {
  return JOBS[code] ?? { label: code, description: '', owner: '—' }
}
