import { DashboardChartDimensionEnum, DashboardChartMeasureCalculationEnum, DashboardChartMeasureMetricEnum, DashboardChartSeriesEnum, DashboardChartTypeEnum, DashboardChartValueFormatEnum, type DashboardFilters, type DashboardWidget, type ProjectWorklog, type ProjectWorklogEntry, type ProjectWorklogMember } from '@yumpoo/api-client'
import { defaultChart } from '../dashboard/chartModel'
import { emptyFilters, newWidget } from '../dashboard/dashboardModel'
import { addDays, todayIn, toApiDate } from '../team/teamDashboardModel'

export interface ProjectStatisticsTemplate {
  inProgress: DashboardWidget; todo: DashboardWidget; overdue: DashboardWidget; duration: DashboardWidget
  workload: DashboardWidget; status: DashboardWidget
}
export interface WorklogGroup extends ProjectWorklogMember { totalMs: number; entries: ProjectWorklogEntry[] }

/** Archived work items stay in the global scope so cumulative time keeps them; count widgets drop them locally. */
export const projectStatisticsFilters = (): DashboardFilters => ({ ...emptyFilters(), includeArchived: true })
const live = (patch: Partial<DashboardFilters> = {}): DashboardFilters => ({ ...emptyFilters(), ...patch })

function chart(title: string, patch: Partial<NonNullable<DashboardWidget['chart']>>): DashboardWidget {
  return { ...newWidget('CHART'), title, chart: { ...defaultChart(), ...patch } }
}
const number = (title: string, metric: DashboardChartMeasureMetricEnum, filters: DashboardFilters | null) =>
  chart(title, { type: DashboardChartTypeEnum.Number, dimension: DashboardChartDimensionEnum.None, measure: { metric, calculation: DashboardChartMeasureCalculationEnum.Sum }, filters })

export function projectStatisticsTemplate(timezone: string): ProjectStatisticsTemplate {
  return {
    inProgress: number('进行中', DashboardChartMeasureMetricEnum.Total, live({ categories: ['IN_PROGRESS'] })),
    todo: number('待开始', DashboardChartMeasureMetricEnum.Total, live({ categories: ['TODO'] })),
    overdue: number('逾期', DashboardChartMeasureMetricEnum.Total, live({ categories: ['TODO', 'IN_PROGRESS'], dueTo: toApiDate(addDays(todayIn(timezone), -1)) })),
    duration: number('项目累计耗时', DashboardChartMeasureMetricEnum.Duration, null),
    workload: chart('成员当前工作量', { type: DashboardChartTypeEnum.Bar, dimension: DashboardChartDimensionEnum.Assignee, series: DashboardChartSeriesEnum.Category, stacked: true, filters: live({ categories: ['TODO', 'IN_PROGRESS'] }) }),
    status: chart('状态分布', { type: DashboardChartTypeEnum.Donut, dimension: DashboardChartDimensionEnum.Status, valueFormat: DashboardChartValueFormatEnum.Percent, filters: live() }),
  }
}

export function worklogGroups(log: ProjectWorklog): WorklogGroup[] {
  const groups = new Map(log.members.map(member => [member.userId, { ...member, totalMs: 0, entries: [] as ProjectWorklogEntry[] }]))
  for (const entry of log.entries) {
    const group = groups.get(entry.userId)!
    group.entries.push(entry)
    group.totalMs += entry.durationMs
  }
  for (const group of groups.values()) group.entries.sort((a, b) => b.durationMs - a.durationMs)
  return [...groups.values()].sort((a, b) => b.totalMs - a.totalMs)
}
