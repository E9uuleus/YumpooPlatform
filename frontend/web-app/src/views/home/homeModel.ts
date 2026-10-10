import { TeamMemberTaskStatusCategoryEnum, type DashboardProject, type TeamMemberTask } from '@yumpoo/api-client'
import { addDays } from '../../components/dashboard/team/teamDashboardModel'
import { companyDateTime } from '../../components/timer/timeFormat'
import type { HeroDaypart } from '../../motion/scenes/auroraShapes'

export type TaskFilter = 'ALL' | TeamMemberTaskStatusCategoryEnum.InProgress | TeamMemberTaskStatusCategoryEnum.Todo

export interface HubProject {
  id: string
  name: string
  code: string
  inProgress: number
  todo: number
  overdue: number
}

const WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']

function companyClock(now: Date, timezone: string): { date: string; minutes: number } {
  const value = companyDateTime(now, timezone)
  return { date: value.slice(0, 10), minutes: Number(value.slice(11, 13)) * 60 + Number(value.slice(14, 16)) }
}

export function greeting(now: Date, timezone: string): string {
  const { minutes } = companyClock(now, timezone)
  if (minutes < 5 * 60) return '夜深了'
  if (minutes < 9 * 60) return '早上好'
  if (minutes < 11 * 60 + 30) return '上午好'
  if (minutes < 13 * 60 + 30) return '中午好'
  if (minutes < 18 * 60) return '下午好'
  return '晚上好'
}

/** Picks the hero aurora palette; the boundaries are looser than the greeting so colours change less often. */
export function daypart(now: Date, timezone: string): HeroDaypart {
  const { minutes } = companyClock(now, timezone)
  if (minutes < 5 * 60 || minutes >= 18 * 60) return 'night'
  if (minutes < 11 * 60) return 'morning'
  if (minutes < 14 * 60) return 'noon'
  return 'afternoon'
}

/** `10月10日 星期六` on the company calendar. */
export function heroDate(now: Date, timezone: string): string {
  const date = new Date(`${companyClock(now, timezone).date}T00:00:00Z`)
  return `${date.getUTCMonth() + 1}月${date.getUTCDate()}日 ${WEEKDAYS[date.getUTCDay()]}`
}

/** Monday-first week that contains `today`. */
export function weekDays(today: string): string[] {
  const offset = (new Date(`${today}T00:00:00Z`).getUTCDay() + 6) % 7
  return Array.from({ length: 7 }, (_, index) => addDays(today, index - offset))
}

const inProgress = (task: TeamMemberTask) => task.statusCategory === TeamMemberTaskStatusCategoryEnum.InProgress

/** In-progress work first; the server order (due date, then recency) is kept inside each group. */
export function sortTasks(tasks: TeamMemberTask[]): TeamMemberTask[] {
  return [...tasks.filter(inProgress), ...tasks.filter(task => !inProgress(task))]
}

/** Member projects with the viewer's open-task counts; projects needing attention come first. */
export function hubProjects(projects: DashboardProject[], tasks: TeamMemberTask[]): HubProject[] {
  const rows = new Map<string, HubProject>(projects.map(project => [project.id,
    { id: project.id, name: project.name, code: project.code, inProgress: 0, todo: 0, overdue: 0 }]))
  for (const task of tasks) {
    const row = rows.get(task.projectId)
    if (!row) continue
    if (inProgress(task)) row.inProgress += 1
    else row.todo += 1
    if (task.overdue) row.overdue += 1
  }
  return [...rows.values()].sort((a, b) => b.overdue - a.overdue
    || (b.inProgress + b.todo) - (a.inProgress + a.todo)
    || a.name.localeCompare(b.name, 'zh-CN'))
}
