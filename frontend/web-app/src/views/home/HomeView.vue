<script setup lang="ts">
import { usePageLoad } from '../../brand/page-progress'

import { DataAnalysis, DataLine, Monitor, FolderOpened, Message, OfficeBuilding, Timer } from '@element-plus/icons-vue'
import { ProjectLifecycleFilter, TeamMemberTaskStatusCategoryEnum, type DashboardProject, type TeamMemberTask, type TeamTimesheet } from '@yumpoo/api-client'
import { ElTooltip } from 'element-plus'
import { computed, inject, onBeforeUnmount, onMounted, ref, type Component } from 'vue'
import { useRouter } from 'vue-router'
import { dashboardsApi, myWorkApi, projectsApi } from '../../api/client'
import { dayTargetMs, monthGrid, monthStats, runningExtraMs, shiftMonth, weekdayLabels } from '../../components/my-time/myTimeModel'
import { addDays, buildTimesheet, fromApiDate, projectColors, shortHours, todayIn, toApiDate } from '../../components/team/teamDashboardModel'
import AuroraShapes from '../../components/motion/AuroraShapes.vue'
import { MY_PROJECT_ACCESSES } from '../../components/projects/projectScope'
import { workItemLabelColorValue } from '../../components/projects/workItemLabelColors'
import YpJumpCard, { type JumpTone } from '../../components/yp/YpJumpCard.vue'
import YpSegmented from '../../components/yp/YpSegmented.vue'
import { useInbox } from '../../composables/useInbox'
import { operationsAlertSummaryContext } from '../../components/operations/operationsContext'
import { useSession } from '../../composables/useSession'
import { formatDuration, onTimeTrackingChanged, useTimeTracker } from '../../composables/useTimeTracker'
import { onWorkItemChanged } from '../../composables/workItemChanges'
import { usePageOnboarding } from '../../onboarding/useOnboarding'
import { daypart, filterTasks, greeting, heroDate, hubProjects, isInProgressTask, isNotStartedTask, openCount, sortTasks, type HubProject, type TaskFilter } from './homeModel'
import { timerTitle } from '../../components/timer/timeCategories'

const pageLoad = usePageLoad()
interface JumpCard { key: string; label: string; meta: string; icon: Component; tone: JumpTone; open: () => void }

const router = useRouter(), session = useSession(), tracker = useTimeTracker(), inbox = useInbox()
const alerts = inject(operationsAlertSummaryContext)!
const alertCount = computed(() => (alerts.value?.critical ?? 0) + (alerts.value?.warning ?? 0))
const onboarding = usePageOnboarding()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
// The tracker clock is server-calibrated and ticks every second, so the greeting follows the company time of day.
const now = computed(() => new Date(tracker.now.value))
const hero = computed(() => ({ date: heroDate(now.value, timezone.value), greeting: greeting(now.value, timezone.value), daypart: daypart(now.value, timezone.value) }))
const today = computed(() => todayIn(timezone.value, now.value))
const month = computed(() => today.value.slice(0, 7))
const tasks = ref<TeamMemberTask[]>([]), projects = ref<DashboardProject[]>([]), monthSheet = ref<TeamTimesheet>(), projectTotal = ref<number>()
const filter = ref<TaskFilter>('ALL')
const running = computed(() => tracker.running.value.length > 0)
const startedAt = computed(() => running.value ? new Date(Math.min(...tracker.running.value.map(timer => timer.session.startedAt.getTime()))) : undefined)
const runningTitle = computed(() => {
  const first = tracker.running.value[0]
  return `${first ? timerTitle(first, '工作项') : '工作项'}${tracker.running.value.length > 1 ? ` 等 ${tracker.running.value.length} 项` : ''}`
})

const counts = computed(() => ({
  inProgress: tasks.value.filter(isInProgressTask).length,
  todo: tasks.value.filter(isNotStartedTask).length,
  overdue: tasks.value.filter(task => task.overdue).length,
}))
const sortedTasks = computed(() => sortTasks(tasks.value))
const visibleTasks = computed(() => filterTasks(sortedTasks.value, filter.value))
const taskOptions = computed<{ value: TaskFilter; label: string }[]>(() => [
  { value: 'ALL', label: `全部 ${tasks.value.length}` },
  { value: TeamMemberTaskStatusCategoryEnum.InProgress, label: `进行中 ${counts.value.inProgress}` },
  { value: TeamMemberTaskStatusCategoryEnum.Todo, label: `待开始 ${counts.value.todo}` },
])
const homeProjects = computed(() => hubProjects(projects.value, tasks.value).slice(0, 6))
const colors = computed(() => projectColors([...projects.value.map(project => project.id), ...tasks.value.map(task => task.projectId)]))
// Rows holding only next-month days are dropped so the card stays compact.
const calendar = computed(() => {
  const cells = monthGrid(month.value, today.value)
  const lastRow = cells.reduce((row, cell) => cell.inMonth ? cell.row : row, 0)
  return cells.slice(0, (lastRow + 1) * 7)
})
const recordedDays = computed(() => monthSheet.value ? buildTimesheet(monthSheet.value).rows[0]?.days ?? {} : {})
const monthDays = computed<Record<string, number>>(() => {
  const extra = monthSheet.value ? runningExtraMs(monthSheet.value.asOf, startedAt.value, tracker.now.value) : 0
  return extra ? { ...recordedDays.value, [today.value]: (recordedDays.value[today.value] ?? 0) + extra } : recordedDays.value
})
const monthTotal = computed(() => monthStats(monthDays.value, month.value).totalMs)
const jumps = computed<JumpCard[]>(() => [
  { key: 'projects', label: '管理项目', meta: projectTotal.value === undefined ? '全部项目目录' : `${projectTotal.value} 个进行中项目`,
    icon: FolderOpened, tone: 'blue', open: () => openWorkspace('workspace') },
  session.isCompanyAdmin.value
    ? { key: 'team', label: '团队视图', meta: '成员与项目工时、加班与当前任务', icon: DataLine, tone: 'purple', open: () => void router.push({ name: 'team', params: { view: 'timesheet' } }) }
    : { key: 'dashboards', label: '仪表板', meta: '个人看板与统计', icon: DataAnalysis, tone: 'purple', open: () => openWorkspace('dashboards') },
  { key: 'inbox', label: '收件箱', meta: inbox.unread.value ? `${inbox.unread.value} 条未读` : '没有未读消息', icon: Message, tone: 'red',
    open: () => void router.push({ name: 'inbox' }) },
  tracker.timerEnabled.value || running.value
    ? { key: 'timer', label: running.value ? '计时中' : '开始计时', meta: running.value ? formatDuration(tracker.wallDuration.value) : '选择工作项开始计时',
      icon: Timer, tone: 'green', open: () => window.dispatchEvent(new Event('yumpoo:open-timer')) }
    : { key: 'timer', label: '记录工时', meta: '在今天的时间线上按时段填写', icon: Timer, tone: 'green', open: openToday },
  ...(session.isCompanyAdmin.value
    ? [{ key: 'company', label: '公司管理', meta: '项目、成员与组织', icon: OfficeBuilding, tone: 'amber' as const, open: () => void router.push({ name: 'company-overview' }) }]
    : []),
  ...(session.isPlatformAdmin.value
    ? [{ key: 'operations', label: '运维', meta: alertCount.value ? `${alertCount.value} 条告警` : '运行正常', icon: Monitor, tone: 'teal' as const, open: () => void router.push({ name: 'operations-overview' }) }]
    : []),
])

function openWorkspace(name: 'workspace' | 'dashboards' | 'my-time'): void {
  const workspaceSlug = session.authentication.value?.user.workspaceSlug
  if (workspaceSlug) void router.push({ name, params: { workspaceSlug } })
}
function openToday(): void {
  const workspaceSlug = session.authentication.value?.user.workspaceSlug
  if (workspaceSlug) void router.push({ name: 'my-time-day', params: { workspaceSlug, date: today.value } })
}
function openTask(task: TeamMemberTask): void {
  void router.push({ name: 'project-overview', params: { projectId: task.projectId }, query: { workItemId: task.id } })
}
function openProject(project: HubProject): void {
  void router.push({ name: 'project-overview', params: { projectId: project.id } })
}
const dueLabel = (date: Date) => fromApiDate(date).slice(5).replace('-', '/')
const statusStyle = (task: TeamMemberTask) => ({ backgroundColor: workItemLabelColorValue(task.statusColor), color: 'var(--yp-text-inverse)' })
function calendarDuration(ms: number): string {
  const minutes = Math.floor(Math.max(0, ms) / 60_000)
  return `${Math.floor(minutes / 60)} H ${String(minutes % 60).padStart(2, '0')} m`
}

const quiet = () => undefined
function loadMonth() {
  return myWorkApi.getMyTimesheet({ from: toApiDate(`${month.value}-01`), to: toApiDate(addDays(`${shiftMonth(month.value, 1)}-01`, -1)) })
    .then(sheet => { monthSheet.value = sheet }, quiet)
}
function loadTasks() {
  return myWorkApi.listMyCurrentTasks({ offset: 0, limit: 100 }).then(page => { tasks.value = page.items }, quiet)
}
void pageLoad.track(loadTasks)
void pageLoad.track(() => dashboardsApi.listDashboardProjects({ includeArchived: false, limit: 50 }).then(page => { projects.value = page.items }, quiet))
void pageLoad.track(() => projectsApi.listProjects({ lifecycle: ProjectLifecycleFilter.Active, ended: false, actorAccesses: MY_PROJECT_ACCESSES, page: 0, size: 1 }).then(page => { projectTotal.value = page.totalElements }, quiet))
void pageLoad.track(loadMonth)
onBeforeUnmount(onTimeTrackingChanged(loadMonth))
onBeforeUnmount(onWorkItemChanged(loadTasks))
onMounted(() => { void onboarding.ready() })
</script>

<template>
  <section class="home-hub">
    <header class="home-hero">
      <AuroraShapes :daypart="hero.daypart" />
      <div class="home-hero__text">
        <p class="home-hero__date">
          {{ hero.date }}
        </p>
        <h1>{{ hero.greeting }}，{{ session.authentication.value?.user.displayName }}</h1>
        <div class="home-hero__chips">
          <span
            v-if="running"
            class="home-chip"
          ><i
            class="home-chip__live"
            aria-hidden="true"
          /><span class="home-chip__text">计时中 · {{ runningTitle }}</span></span>
          <span class="home-chip">{{ counts.inProgress }} 项进行中</span>
          <span
            v-if="counts.overdue"
            class="home-chip home-chip--danger"
          >{{ counts.overdue }} 项逾期</span>
          <span
            v-if="session.isPlatformAdmin.value && alertCount"
            class="home-chip home-chip--danger"
          >运维告警 {{ alertCount }}</span>
          <span
            v-if="inbox.unread.value"
            class="home-chip"
          >{{ inbox.unread.value }} 条未读</span>
        </div>
      </div>
    </header>

    <nav
      class="home-jumps"
      data-tour="home-jumps"
      aria-label="快捷入口"
    >
      <yp-jump-card
        v-for="(card, index) in jumps"
        :key="card.key"
        :label="card.label"
        :meta="card.meta"
        :icon="card.icon"
        :tone="card.tone"
        :index="index"
        @open="card.open"
      />
    </nav>

    <div class="home-columns">
      <section
        class="home-panel home-panel--tasks"
        data-tour="home-tasks"
        aria-labelledby="home-tasks-title"
      >
        <header class="home-panel__head">
          <h2 id="home-tasks-title">
            我的工作项
          </h2>
          <YpSegmented
            v-model="filter"
            :options="taskOptions"
            label="筛选我的工作项"
          />
        </header>
        <div class="home-tasks">
          <button
            v-for="task in visibleTasks"
            :key="task.id"
            type="button"
            class="home-task"
            @click="openTask(task)"
          >
            <span
              class="home-task__accent"
              :style="{ background: colors.get(task.projectId) }"
            />
            <span class="home-task__text"><strong>{{ task.title }}</strong><small>{{ task.projectName }}</small></span>
            <span
              class="home-task__status"
              :style="statusStyle(task)"
            >{{ task.statusName }}</span>
            <span
              class="home-task__due"
              :class="{ 'is-overdue': task.overdue }"
            >{{ task.dueDate ? `${task.overdue ? '逾期 ' : ''}${dueLabel(task.dueDate)}` : '—' }}</span>
          </button>
          <p
            v-if="!visibleTasks.length"
            class="home-empty"
          >
            没有分配给你的待办工作项
          </p>
        </div>
      </section>

      <div class="home-side">
        <section
          class="home-panel home-panel--projects"
          data-tour="home-projects"
          aria-labelledby="home-projects-title"
        >
          <header class="home-panel__head">
            <h2 id="home-projects-title">
              我的项目
            </h2>
            <button
              type="button"
              class="home-link"
              @click="openWorkspace('workspace')"
            >
              全部项目
            </button>
          </header>
          <button
            v-for="project in homeProjects"
            :key="project.id"
            type="button"
            class="home-project"
            @click="openProject(project)"
          >
            <span
              class="home-project__mark"
              :style="{ '--mark': colors.get(project.id) }"
            >{{ project.name.slice(0, 1) }}</span>
            <span class="home-project__text">
              <strong>{{ project.name }}</strong>
              <small v-if="openCount(project)">
                {{ project.inProgress }} 进行中 · {{ project.todo }} 待开始<template v-if="project.other">
                  · {{ project.other }} 其他
                </template><b
                  v-if="project.overdue"
                  class="home-project__overdue"
                > · {{ project.overdue }} 逾期</b>
              </small>
              <small v-else>暂无分配给你的工作项</small>
              <span class="home-project__bar"><i
                v-if="project.inProgress"
                class="is-progress"
                :style="{ flex: project.inProgress }"
              /><i
                v-if="project.todo"
                class="is-todo"
                :style="{ flex: project.todo }"
              /><i
                v-if="project.other"
                class="is-other"
                :style="{ flex: project.other }"
              /></span>
            </span>
          </button>
          <p
            v-if="!homeProjects.length"
            class="home-empty"
          >
            你还没有加入进行中的项目
          </p>
        </section>
        <section
          class="home-panel home-panel--calendar"
          data-tour="home-calendar"
          aria-labelledby="home-calendar-title"
        >
          <header class="home-panel__head">
            <h2 id="home-calendar-title">
              工时日历<small class="home-calendar__total">{{ Number(month.slice(5)) }}月 · 合计 {{ shortHours(monthTotal) }}h</small>
            </h2>
            <button
              type="button"
              class="home-link"
              @click="openWorkspace('my-time')"
            >
              查看明细
            </button>
          </header>
          <div
            class="home-calendar"
            role="group"
            :aria-label="`${Number(month.slice(5))}月每日工时`"
          >
            <span
              v-for="label in weekdayLabels"
              :key="label"
              class="home-calendar__weekday"
              aria-hidden="true"
            >{{ label }}</span>
            <el-tooltip
              v-for="cell in calendar"
              :key="cell.date"
              :content="calendarDuration(monthDays[cell.date] ?? 0)"
              :disabled="!cell.inMonth || (monthDays[cell.date] ?? 0) <= 0"
              placement="top"
            >
              <span
                class="home-calendar__day"
                :class="{
                  'is-recorded': cell.inMonth && (monthDays[cell.date] ?? 0) > 0,
                  'is-full': cell.inMonth && (monthDays[cell.date] ?? 0) >= dayTargetMs(monthSheet?.days, cell.date),
                  'is-today': cell.today,
                }"
                :style="{ '--d': cell.row + cell.column }"
              >{{ cell.inMonth ? cell.day : '' }}</span>
            </el-tooltip>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.home-hub {
  position: relative;
  isolation: isolate;
  display: grid;
  gap: var(--yp-space-5);
}

.home-hero {
  position: relative;
  overflow: hidden;
  min-height: 220px;
  border-radius: var(--yp-radius-xl);
  background: var(--yp-bg-surface);
}

.home-hero__text {
  position: relative;
  z-index: 1;
  display: flex;
  box-sizing: border-box;
  flex-direction: column;
  justify-content: center;
  gap: var(--yp-space-2);
  max-width: 520px;
  min-height: 220px;
  padding: var(--yp-space-6) var(--yp-space-8);
}

.home-hero__date {
  margin: 0;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
  letter-spacing: 0.04em;
}

.home-hero h1 {
  margin: 0;
  font-family: var(--yp-font-heading);
  font-size: var(--yp-type-page-title-size);
  font-weight: 600;
  line-height: var(--yp-type-page-title-line);
  letter-spacing: -0.01em;
}

.home-hero__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--yp-space-2);
  margin-top: var(--yp-space-1);
}

.home-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 10px;
  border-radius: var(--yp-radius-pill);
  color: var(--yp-text-primary);
  font-size: 12px;
  white-space: nowrap;
  background: var(--yp-fill-neutral);
}

.home-chip--danger {
  color: var(--yp-status-red);
}

.home-chip__text {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.home-chip__live {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--yp-status-green);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--yp-status-green) 22%, transparent);
}

.home-jumps {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--yp-space-3);
}

.home-columns {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  align-items: stretch;
  gap: var(--yp-space-4);
}

.home-side {
  display: grid;
  align-content: start;
  gap: var(--yp-space-4);
  min-width: 0;
}

/* Size containment lets the right column set the row height; the task list scrolls inside it. */
.home-panel--tasks {
  display: flex;
  flex-direction: column;
  min-height: 420px;
  contain: size;
}

.home-panel--tasks .home-tasks {
  flex: 1;
  min-height: 0;
  max-height: none;
}

.home-calendar__total {
  color: var(--yp-text-muted);
  font-size: 12px;
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}

.home-panel--calendar h2 {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  column-gap: var(--yp-space-2);
}

.home-calendar {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 4px;
}

.home-calendar__weekday {
  padding-bottom: 2px;
  color: var(--yp-text-muted);
  font-size: 12px;
  text-align: center;
}

.home-calendar__day {
  display: grid;
  height: 30px;
  place-items: center;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-secondary);
  font: 500 12px/1 var(--yp-font-mono);
  font-variant-numeric: tabular-nums;
  animation: home-rise 280ms var(--yp-ease-standard) backwards;
  animation-delay: calc(var(--d) * 12ms + 480ms);
}

.home-calendar__day.is-recorded {
  background: color-mix(in srgb, var(--yp-action-primary) 18%, transparent);
  color: var(--yp-text-primary);
}

.home-calendar__day.is-full {
  background: var(--yp-action-primary);
  color: var(--yp-status-blue-foreground);
}

.home-calendar__day.is-today {
  box-shadow: inset 0 0 0 1.5px var(--yp-action-primary);
}

.home-panel {
  min-width: 0;
  padding: var(--yp-space-4) var(--yp-space-5);
  border-radius: var(--yp-radius-xl);
  animation: home-rise 360ms var(--yp-ease-standard) backwards;
  animation-delay: 320ms;
  background: var(--yp-bg-surface);
}

.home-panel--projects {
  animation-delay: 380ms;
}

.home-panel--calendar {
  animation-delay: 440ms;
}

.home-panel__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--yp-space-3);
  margin-bottom: var(--yp-space-3);
}

.home-panel__head h2 {
  margin: 0;
  font-size: var(--yp-type-card-title-size);
  font-weight: 600;
  line-height: var(--yp-type-card-title-line);
}

.home-tasks {
  display: grid;
  gap: 2px;
  max-height: 376px;
  overflow: auto;
  overscroll-behavior: contain;
}

.home-task,
.home-project {
  display: grid;
  align-items: center;
  gap: var(--yp-space-3);
  width: 100%;
  padding: var(--yp-space-2);
  border: 0;
  border-radius: var(--yp-radius-md);
  background: transparent;
  color: var(--yp-text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard);
}

.home-task {
  grid-template-columns: 4px minmax(0, 1fr) auto 72px;
}

.home-project {
  grid-template-columns: 34px minmax(0, 1fr);
}

.home-task:hover,
.home-project:hover {
  background: var(--yp-bg-hover);
}

.home-task:focus-visible,
.home-project:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: -2px;
}

.home-task__accent {
  width: 4px;
  height: 30px;
  border-radius: 2px;
}

.home-task__text,
.home-project__text {
  display: grid;
  min-width: 0;
  line-height: 18px;
}

.home-task__text strong,
.home-project__text strong {
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-task__text small,
.home-project__text small {
  overflow: hidden;
  color: var(--yp-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-task__status {
  display: grid;
  min-width: 56px;
  height: 24px;
  padding: 0 var(--yp-space-2);
  place-items: center;
  border-radius: var(--yp-radius-xs);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.home-task__due {
  color: var(--yp-text-secondary);
  font: 500 12px/1 var(--yp-font-mono);
  text-align: right;
  white-space: nowrap;
}

.home-task__due.is-overdue {
  color: var(--yp-status-red);
  font-weight: 700;
}

.home-project__mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  color: var(--yp-status-blue-foreground);
  font-family: var(--yp-font-heading);
  font-size: 14px;
  font-weight: 600;
  background: var(--mark);
}

.home-project__overdue {
  color: var(--yp-status-red);
  font-weight: 500;
}

.home-project__bar {
  display: flex;
  height: 4px;
  margin-top: 6px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--yp-bg-sunken);
}

.home-project__bar .is-progress {
  background: var(--yp-status-orange);
}

.home-project__bar .is-todo {
  background: var(--yp-label-gray);
}

.home-project__bar .is-other {
  background: var(--yp-border-strong);
}

.home-link {
  flex-shrink: 0;
  padding: 0;
  border: 0;
  background: none;
  color: var(--yp-link);
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
}

.home-empty {
  margin: 0;
  padding: var(--yp-space-6) 0;
  color: var(--yp-text-muted);
  font-size: 13px;
  text-align: center;
}

@keyframes home-rise {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .home-panel,
  .home-calendar__day {
    animation: none;
  }
}

@media (max-width: 1099.98px) {
  .home-columns {
    grid-template-columns: minmax(0, 1fr);
  }

  .home-panel--tasks {
    min-height: 0;
    contain: none;
  }

  .home-panel--tasks .home-tasks {
    max-height: 376px;
  }
}

@media (max-width: 640px) {
  .home-hero__text {
    max-width: none;
    padding: var(--yp-space-5);
  }

  .home-hero h1 {
    font-size: 22px;
    line-height: 30px;
  }

  .home-task {
    grid-template-columns: 4px minmax(0, 1fr) auto;
  }

  .home-task__due {
    display: none;
  }
}
</style>
