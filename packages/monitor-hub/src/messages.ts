import { safeText } from './delivery.js'

const beijing = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hour12: false,
})

export function formatBeijing(ms: number) {
  const parts = new Map(beijing.formatToParts(ms).map(part => [part.type, part.value]))
  return `${parts.get('year')}-${parts.get('month')}-${parts.get('day')} ${parts.get('hour')}:${parts.get('minute')}`
}

export function maintenanceAnnouncement(title: string, serviceName: string, starts: number, ends: number) {
  return `计划维护：${safeText(title)}\n服务：${serviceName}\n开始：${formatBeijing(starts)}（北京时间）\n结束：${formatBeijing(ends)}（北京时间）`
}

export function maintenanceChange(action: 'postpone' | 'end' | 'cancel', title: string, ends?: number) {
  const name = safeText(title)
  if (action === 'postpone') {
    if (ends === undefined) throw new Error('延期需要新的结束时间')
    return `维护安排「${name}」已延期，预计结束：${formatBeijing(ends)}（北京时间）。`
  }
  return `维护安排「${name}」${action === 'end' ? '已结束，服务恢复' : '已取消'}。`
}
