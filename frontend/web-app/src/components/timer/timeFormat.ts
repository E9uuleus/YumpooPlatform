export function companyDateTime(date: Date, timezone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(date)
  const part = (key: string) => parts.find(value => value.type === key)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}:${part('second')}`
}

export function parseCompanyDateTime(value: string, timezone: string): Date {
  const target = new Date(`${value}Z`).getTime()
  if (!Number.isFinite(target)) throw new Error('请输入完整日期和时间')
  let result = target
  for (let i = 0; i < 4; i++) {
    const local = new Date(`${companyDateTime(new Date(result), timezone)}Z`).getTime()
    if (local === target) return new Date(result)
    result += target - local
  }
  throw new Error('该时间在公司时区不存在，请检查夏令时切换')
}

const pad = (value: number) => String(value).padStart(2, '0')

/** Compact orb label: minutes and seconds within the first hour, then hours and minutes (`1h25`) so it never reads as mm:ss. */
export function orbDuration(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000)), hours = Math.floor(seconds / 3600), minutes = Math.floor(seconds / 60) % 60
  return hours ? `${hours}h${pad(minutes)}` : `${pad(minutes)}:${pad(seconds % 60)}`
}

/** Two stacked readings with units for the narrow edge tab: minutes and seconds within the first hour, then hours and minutes. */
export function dockDuration(ms: number): { major: string; majorUnit: string; minor: string; minorUnit: string } {
  const seconds = Math.max(0, Math.floor(ms / 1000)), hours = Math.floor(seconds / 3600), minutes = Math.floor(seconds / 60) % 60
  return hours ? { major: String(hours), majorUnit: '时', minor: pad(minutes), minorUnit: '分' } : { major: String(minutes), majorUnit: '分', minor: pad(seconds % 60), minorUnit: '秒' }
}
