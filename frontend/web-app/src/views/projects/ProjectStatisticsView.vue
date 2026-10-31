<script setup lang="ts">
import YpNotice from '../../components/yp/YpNotice.vue'
import { usePageLoad } from '../../brand/page-progress'

import { ArrowLeft, ArrowRight, Refresh } from '@element-plus/icons-vue'
import { ProjectLifecycle, readCsrfToken, type DashboardChartResult, type DashboardChartSelection, type DashboardWidget, type ProjectDetail, type ProjectWorklog } from '@yumpoo/api-client'
import { ElButton, ElTag, ElTooltip } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectStatisticsApi, projectsApi } from '../../api/client'
import { problemMessage, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import DashboardChart from '../../components/dashboard/DashboardChart.vue'
import DashboardExploreDialog from '../../components/dashboard/DashboardExploreDialog.vue'
import { projectStatisticsSource } from '../../components/dashboard/statisticsSource'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import { projectStatisticsFilters, projectStatisticsTemplate, worklogGroups } from '../../components/project-statistics/projectStatisticsTemplate'
import { hoursMinutes, periodFor, periodLabel, shiftPeriod, todayIn, toApiDate, type Period } from '../../components/team/teamDashboardModel'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import { useSession } from '../../composables/useSession'
import { formatTimestamp } from '../../design-system/dates'

const pageLoad = usePageLoad()
const route = useRoute(), router = useRouter(), session = useSession()
const projectId = String(route.params.projectId)
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const source = projectStatisticsSource(projectId)
const filters = projectStatisticsFilters()
const project = ref<ProjectDetail>(), projectError = ref<ApiProblem>()
const template = ref(projectStatisticsTemplate(timezone.value))
const metrics = computed(() => [template.value.inProgress, template.value.todo, template.value.overdue, template.value.duration])
const results = ref(new Map<string, DashboardChartResult>()), asOf = ref<Date>(), loading = ref(false), error = ref('')
const detail = ref<{ widget: DashboardWidget; selection?: DashboardChartSelection | undefined }>()
const connections = computed(() => project.value
  ? [{ id: projectId, name: project.value.name, code: project.value.code, lifecycle: 'ACTIVE', available: true }]
  : [])
const thisWeek = computed(() => periodFor('WEEK', todayIn(timezone.value)))
const period = ref<Period>(shiftPeriod(periodFor('WEEK', todayIn(timezone.value)), -1))
const worklog = ref<ProjectWorklog>(), worklogLoading = ref(false), worklogError = ref('')
const groups = computed(() => worklog.value ? worklogGroups(worklog.value) : [])
const weekTotal = computed(() => groups.value.reduce((sum, group) => sum + group.totalMs, 0))
let worklogSequence = 0

async function loadCharts(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const snapshot = await projectStatisticsApi.queryProjectStatistics({ projectId, xXSRFTOKEN: readCsrfToken() || '',
      projectStatisticsQuery: { filters, widgets: Object.values(template.value) } })
    results.value = new Map(snapshot.charts.map(result => [result.id, result]))
    asOf.value = snapshot.asOf
  } catch (reason) {
    error.value = problemMessage(await toApiProblem(reason))
  } finally {
    loading.value = false
  }
}

async function loadWorklog(): Promise<void> {
  const sequence = ++worklogSequence
  worklogLoading.value = true
  worklogError.value = ''
  try {
    const result = await projectStatisticsApi.getProjectWorklog({ projectId, from: toApiDate(period.value.from), to: toApiDate(period.value.to) })
    if (sequence === worklogSequence) worklog.value = result
  } catch (reason) {
    if (sequence === worklogSequence) worklogError.value = problemMessage(await toApiProblem(reason))
  } finally {
    if (sequence === worklogSequence) worklogLoading.value = false
  }
}

function refresh(): void {
  template.value = projectStatisticsTemplate(timezone.value)
  void loadCharts()
  void loadWorklog()
}

function openWorkItem(workItemId: string): void {
  void router.push({ name: 'project-overview', params: { projectId }, query: { view: 'table', workItemId } })
}

watch(period, () => void loadWorklog())
onMounted(async () => {
  try {
    project.value = await pageLoad.track(() => projectsApi.getProject({ projectId }))
  } catch (reason) {
    projectError.value = await toApiProblem(reason)
    return
  }
  if (project.value.lifecycle !== ProjectLifecycle.Archived) await pageLoad.track(() => Promise.all([loadCharts(), loadWorklog()]))
})
</script>

<template>
  <div class="project-view-stack">
    <project-workspace-header
      section="statistics"
      :project="project"
      title="项目统计"
    />
    <inline-problem
      v-if="projectError"
      :problem="projectError"
    />
    <yp-empty-state
      v-else-if="project?.lifecycle === ProjectLifecycle.Archived"
      title="项目已归档，不计入统计"
      description=""
    />
    <section
      v-else-if="project"
      class="project-statistics"
      aria-labelledby="project-statistics-title"
    >
      <header class="project-page-heading">
        <div>
          <h2 id="project-statistics-title">
            统计
          </h2>
          <p v-if="asOf">
            统计至 {{ formatTimestamp(asOf, timezone).slice(11) }}
          </p>
        </div>
        <el-tooltip content="刷新">
          <el-button
            :icon="Refresh"
            text
            aria-label="刷新统计"
            :loading="loading || worklogLoading"
            @click="refresh"
          />
        </el-tooltip>
      </header>
      <yp-notice
        v-if="error"
        tone="danger"
      >
        {{ error }}
      </yp-notice>
      <div class="project-statistics__metrics">
        <article
          v-for="widget in metrics"
          :key="widget.id"
          class="project-statistics__card project-statistics__card--metric"
        >
          <h3>{{ widget.title }}</h3>
          <div class="project-statistics__chart">
            <DashboardChart
              :widget="widget"
              :result="results.get(widget.id)"
              :loading="loading"
              @select="detail = { widget, selection: $event }"
            />
          </div>
        </article>
      </div>
      <div class="project-statistics__charts">
        <article
          v-for="widget in [template.workload, template.status]"
          :key="widget.id"
          class="project-statistics__card"
        >
          <h3>{{ widget.title }}</h3>
          <div class="project-statistics__chart">
            <DashboardChart
              :widget="widget"
              :result="results.get(widget.id)"
              :loading="loading"
              @select="detail = { widget, selection: $event }"
            />
          </div>
        </article>
      </div>
      <article class="project-statistics__card">
        <div class="project-statistics__worklog-head">
          <h3>成员周工作项</h3>
          <div class="project-statistics__week">
            <el-button
              :icon="ArrowLeft"
              text
              aria-label="上一周"
              @click="period = shiftPeriod(period, -1)"
            />
            <span>{{ periodLabel(period) }}</span>
            <el-button
              :icon="ArrowRight"
              text
              aria-label="下一周"
              :disabled="period.from >= thisWeek.from"
              @click="period = shiftPeriod(period, 1)"
            />
          </div>
          <span class="project-statistics__total">合计 {{ hoursMinutes(weekTotal) }}</span>
        </div>
        <yp-notice
          v-if="worklogError"
          tone="danger"
        >
          {{ worklogError }}
        </yp-notice>
        <yp-empty-state
          v-else-if="worklog && !groups.length"
          compact
          title="该周没有计时记录"
          description=""
        />
        <div
          v-for="group in groups"
          :key="group.userId"
          class="project-statistics__member"
        >
          <div class="project-statistics__member-head">
            <strong>{{ group.displayName }}</strong>
            <el-tag
              v-if="!group.projectMember"
              size="small"
              effect="plain"
            >
              已离开项目
            </el-tag>
            <span>{{ hoursMinutes(group.totalMs) }}</span>
          </div>
          <button
            v-for="entry in group.entries"
            :key="entry.workItemId"
            type="button"
            class="project-statistics__item"
            :title="entry.title"
            @click="openWorkItem(entry.workItemId)"
          >
            <span>{{ entry.title }}</span><span>{{ hoursMinutes(entry.durationMs) }}</span>
          </button>
        </div>
      </article>
    </section>
    <DashboardExploreDialog
      v-if="detail"
      :key="detail.widget.id"
      :source="source"
      :widget="detail.widget"
      :result="results.get(detail.widget.id)"
      :filters="filters"
      :projects="connections"
      :initial-selection="detail.selection"
      :loading="loading"
      hide-settings
      @close="detail = undefined"
      @refresh="loadCharts"
    />
  </div>
</template>

<style scoped>
.project-statistics { display: grid; gap: var(--yp-space-4); }
.project-statistics__metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--yp-space-3); }
.project-statistics__charts { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 1fr); gap: var(--yp-space-3); }
.project-statistics__card { display: flex; flex-direction: column; min-width: 0; padding: var(--yp-space-4); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.project-statistics__card h3 { margin: 0 0 var(--yp-space-2); font-size: 14px; font-weight: 600; }
.project-statistics__chart { height: 320px; min-height: 0; }
.project-statistics__card--metric .project-statistics__chart { height: 120px; }
.project-statistics__worklog-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--yp-space-3); margin-bottom: var(--yp-space-3); }
.project-statistics__worklog-head h3 { margin: 0; }
.project-statistics__week { display: flex; align-items: center; gap: var(--yp-space-1); font-size: 13px; }
.project-statistics__total { color: var(--yp-text-secondary); font-size: 13px; font-variant-numeric: tabular-nums; }
.project-statistics__member + .project-statistics__member { margin-top: var(--yp-space-3); padding-top: var(--yp-space-3); border-top: 1px solid var(--yp-border-subtle); }
.project-statistics__member-head { display: flex; align-items: center; gap: var(--yp-space-2); margin-bottom: var(--yp-space-1); font-size: 13px; }
.project-statistics__member-head > span:last-child { margin-left: auto; font-variant-numeric: tabular-nums; }
.project-statistics__item { display: flex; justify-content: space-between; gap: var(--yp-space-3); width: 100%; padding: 6px var(--yp-space-2); border: 0; border-radius: var(--yp-radius-sm); background: none; color: var(--yp-text-primary); font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.project-statistics__item:hover { background: var(--yp-bg-hover); }
.project-statistics__item:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: -2px; }
.project-statistics__item span:first-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-statistics__item span:last-child { flex: none; font-variant-numeric: tabular-nums; }
@media (max-width: 960px) {
  .project-statistics__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .project-statistics__charts { grid-template-columns: minmax(0, 1fr); }
}
</style>
