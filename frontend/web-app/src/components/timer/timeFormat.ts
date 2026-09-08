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
