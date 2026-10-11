<script setup lang="ts">
import { Calendar, DataAnalysis, FolderOpened, Message, OfficeBuilding, Timer, TopRight } from '@element-plus/icons-vue'
import { ProjectLifecycleFilter, TeamMemberTaskStatusCategoryEnum, type DashboardProject, type TeamMemberTask, type TeamTimesheet } from '@yumpoo/api-client'
import { ElIcon } from 'element-plus'
import { computed, onBeforeUnmount, ref, type Component } from 'vue'
import { useRouter } from 'vue-router'
import { dashboardsApi, myWorkApi, projectsApi } from '../../api/client'
import { MY_TIME_VIEW_ID } from '../../components/dashboard/dashboardModel'
import { runningExtraMs } from '../../components/dashboard/my-time/myTimeModel'
import { buildTimesheet, fromApiDate, projectColors, shortHours, todayIn, toApiDate } from '../../components/dashboard/team/teamDashboardModel'
import AuroraShapes from '../../components/motion/AuroraShapes.vue'
import { workItemLabelColorValue } from '../../components/projects/workItemLabelColors'
import YpSegmented from '../../components/yp/YpSegmented.vue'
import { useInbox } from '../../composables/useInbox'
import { useSession } from '../../composables/useSession'
import { formatDuration, onTimeTrackingChanged, useTimeTracker } from '../../composables/useTimeTracker'
import { daypart, greeting, heroDate, hubProjects, sortTasks, weekDays, type HubProject, type TaskFilter } from './homeModel'

type JumpTone = 'blue' | 'purple' | 'teal' | 'red' | 'green' | 'amber'
interface JumpCard { key: string; label: string; meta: string; icon: Component; tone: JumpTone; open: () => void }

const router = useRouter(), session = useSession(), tracker = useTimeTracker(), inbox = useInbox()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
// The tracker clock is server-calibrated and ticks every second, so the greeting follows the company time of day.
const now = computed(() => new Date(tracker.now.value))
const hero = computed(() => ({ date: heroDate(now.value, timezone.value), greeting: greeting(now.value, timezone.value), daypart: daypart(now.value, timezone.value) }))
const today = computed(() => todayIn(timezone.value, now.value))
const week = computed(() => weekDays(today.value))
const tasks = ref<TeamMemberTask[]>([]), projects = ref<DashboardProject[]>([]), weekSheet = ref<TeamTimesheet>(), projectTotal = ref<number>()
const filter = ref<TaskFilter>('ALL')
const running = computed(() => tracker.current.value?.session ?? undefined)

const counts = computed(() => ({
  inProgress: tasks.value.filter(task => task.statusCategory === TeamMemberTaskStatusCategoryEnum.InProgress).length,
  todo: tasks.value.filter(task => task.statusCategory === TeamMemberTaskStatusCategoryEnum.Todo).length,
  overdue: tasks.value.filter(task => task.overdue).length,
}))
const sortedTasks = computed(() => sortTasks(tasks.value))
const visibleTasks = computed(() => filter.value === 'ALL' ? sortedTasks.value : sortedTasks.value.filter(task => task.statusCategory === filter.value))
const taskOptions = computed<{ value: TaskFilter; label: string }[]>(() => [
  { value: 'ALL', label: `全部 ${tasks.value.length}` },
  { value: TeamMemberTaskStatusCategoryEnum.InProgress, label: `进行中 ${counts.value.inProgress}` },
  { value: TeamMemberTaskStatusCategoryEnum.Todo, label: `待开始 ${counts.value.todo}` },
])
const homeProjects = computed(() => hubProjects(projects.value, tasks.value).slice(0, 6))
const colors = computed(() => projectColors([...projects.value.map(project => project.id), ...tasks.value.map(task => task.projectId)]))
const weekTotals = computed(() => weekSheet.value ? buildTimesheet(weekSheet.value).rows[0]?.days ?? {} : {})
const weekDaily = computed(() => {
  const extra = weekSheet.value ? runningExtraMs(weekSheet.value.asOf, running.value?.startedAt, tracker.now.value) : 0
  return week.value.map(day => ({ day, today: day === today.value, ms: (weekTotals.value[day] ?? 0) + (day === today.value ? extra : 0) }))
})
const weekMs = computed(() => weekDaily.value.reduce((sum, day) => sum + day.ms, 0))
const jumps = computed<JumpCard[]>(() => [
  { key: 'projects', label: '管理项目', meta: projectTotal.value === undefined ? '全部项目目录' : `${projectTotal.value} 个进行中项目`,
    icon: FolderOpened, tone: 'blue', open: () => openWorkspace('workspace') },
  { key: 'dashboards', label: '仪表板', meta: '个人看板与统计', icon: DataAnalysis, tone: 'purple', open: () => openWorkspace('dashboards') },
  { key: 'time', label: '我的工时', meta: `本周 ${shortHours(weekMs.value)} 小时`, icon: Calendar, tone: 'teal',
    open: () => openWorkspace('dashboards', MY_TIME_VIEW_ID) },
  { key: 'inbox', label: '收件箱', meta: inbox.unread.value ? `${inbox.unread.value} 条未读` : '没有未读消息', icon: Message, tone: 'red',
    open: () => void router.push({ name: 'inbox' }) },
  { key: 'timer', label: running.value ? '计时中' : '开始计时', meta: running.value ? formatDuration(tracker.runningDuration.value) : '选择工作项开始计时',
    icon: Timer, tone: 'green', open: () => window.dispatchEvent(new Event('yumpoo:open-timer')) },
  ...(session.isCompanyAdmin.value
    ? [{ key: 'company', label: '公司管理', meta: '成员、角色与组织', icon: OfficeBuilding, tone: 'amber' as const, open: () => void router.push({ name: 'company-overview' }) }]
    : []),
])

function openWorkspace(name: 'workspace' | 'dashboards', dashboardId?: string): void {
  const workspaceSlug = session.authentication.value?.user.workspaceSlug
  if (workspaceSlug) void router.push({ name, params: { workspaceSlug, ...(dashboardId ? { dashboardId } : {}) } })
}
function openTask(task: TeamMemberTask): void {
  void router.push({ name: 'project-overview', params: { projectId: task.projectId }, query: { workItemId: task.id } })
}
function openProject(project: HubProject): void {
  void router.push({ name: 'project-overview', params: { projectId: project.id } })
}
function spotlight(event: PointerEvent): void {
  const card = event.currentTarget as HTMLElement, bounds = card.getBoundingClientRect()
  card.style.setProperty('--spot-x', `${event.clientX - bounds.left}px`)
  card.style.setProperty('--spot-y', `${event.clientY - bounds.top}px`)
}
const dueLabel = (date: Date) => fromApiDate(date).slice(5).replace('-', '/')
const statusStyle = (task: TeamMemberTask) => ({ backgroundColor: workItemLabelColorValue(task.statusColor), color: 'var(--yp-text-inverse)' })
const sparkHeight = (ms: number) => `${Math.max(2, Math.min(1, ms / 32_400_000) * 18)}px`

const quiet = () => undefined
function loadWeek(): void {
  void myWorkApi.getMyTimesheet({ from: toApiDate(week.value[0]!), to: toApiDate(week.value[6]!) })
    .then(sheet => { weekSheet.value = sheet }, quiet)
}
void myWorkApi.listMyCurrentTasks({ offset: 0, limit: 100 }).then(page => { tasks.value = page.items }, quiet)
void dashboardsApi.listDashboardProjects({ includeArchived: false, limit: 50 }).then(page => { projects.value = page.items }, quiet)
void projectsApi.listProjects({ lifecycle: ProjectLifecycleFilter.Active, page: 0, size: 1 }).then(page => { projectTotal.value = page.totalElements }, quiet)
loadWeek()
onBeforeUnmount(onTimeTrackingChanged(loadWeek))
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
          /><span class="home-chip__text">计时中 · {{ tracker.current.value?.workItemTitle ?? '工作项' }}</span></span>
          <span class="home-chip">{{ counts.inProgress }} 项进行中</span>
          <span
            v-if="counts.overdue"
            class="home-chip home-chip--danger"
          >{{ counts.overdue }} 项逾期</span>
          <span
            v-if="inbox.unread.value"
            class="home-chip"
          >{{ inbox.unread.value }} 条未读</span>
        </div>
      </div>
    </header>

    <nav
      class="home-jumps"
      aria-label="快捷入口"
    >
      <button
        v-for="(card, index) in jumps"
        :key="card.key"
        type="button"
        class="home-jump"
        :class="`home-jump--${card.tone}`"
        :style="{ '--i': index }"
        @pointermove="spotlight"
        @click="card.open"
      >
        <span class="home-jump__icon"><el-icon aria-hidden="true"><component :is="card.icon" /></el-icon></span>
        <el-icon
          class="home-jump__arrow"
          aria-hidden="true"
        >
          <TopRight />
        </el-icon>
        <strong>{{ card.label }}</strong>
        <span class="home-jump__meta">{{ card.meta }}<span
          v-if="card.key === 'time'"
          class="home-spark"
          aria-hidden="true"
        ><i
          v-for="day in weekDaily"
          :key="day.day"
          :class="{ 'is-today': day.today }"
          :style="{ height: sparkHeight(day.ms) }"
        /></span></span>
      </button>
    </nav>

    <div class="home-columns">
      <section
        class="home-panel"
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
            <span class="home-task__text"><strong>{{ task.title }}</strong><small>{{ task.itemNo }} · {{ task.projectName }}</small></span>
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

      <section
        class="home-panel home-panel--projects"
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
            <small v-if="project.inProgress + project.todo">
              {{ project.inProgress }} 进行中 · {{ project.todo }} 待开始<b
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

/* Static aurora wash behind the page so the translucent cards pick up colour. */
.home-hub::before {
  position: absolute;
  z-index: -1;
  top: calc(0px - var(--yp-space-8));
  right: 0;
  left: calc(0px - var(--yp-space-10));
  height: 560px;
  background:
    radial-gradient(60% 70% at 85% 0%, color-mix(in srgb, var(--yp-label-bright-blue) 12%, transparent), transparent 70%),
    radial-gradient(45% 60% at 10% 35%, color-mix(in srgb, var(--yp-label-aquamarine) 9%, transparent), transparent 70%);
  content: "";
  pointer-events: none;
}

.home-hero {
  position: relative;
  overflow: hidden;
  min-height: 220px;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-xl);
  background: var(--yp-bg-surface);
  box-shadow: var(--yp-shadow-card);
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
  border: 1px solid color-mix(in srgb, var(--yp-bg-surface) 70%, var(--yp-border-subtle));
  border-radius: var(--yp-radius-pill);
  background: color-mix(in srgb, var(--yp-bg-surface) 60%, transparent);
  color: var(--yp-text-primary);
  font-size: 12px;
  white-space: nowrap;
  backdrop-filter: blur(8px);
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

.home-jump {
  --from: var(--yp-action-primary);
  --to: var(--yp-label-bright-blue);

  position: relative;
  display: grid;
  justify-items: start;
  gap: 6px;
  min-width: 0;
  padding: var(--yp-space-4);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--yp-border-subtle) 80%, transparent);
  border-radius: var(--yp-radius-xl);
  background:
    radial-gradient(160px circle at var(--spot-x, -200px) var(--spot-y, -200px), color-mix(in srgb, var(--from) 12%, transparent), transparent 70%),
    color-mix(in srgb, var(--yp-bg-surface) 76%, transparent);
  box-shadow: var(--yp-shadow-card);
  color: var(--yp-text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  backdrop-filter: blur(14px) saturate(1.3);
  animation: home-rise 320ms var(--yp-ease-standard) backwards;
  animation-delay: calc(var(--i) * 40ms + 80ms);
  transition:
    transform var(--yp-motion-overlay) var(--yp-ease-standard),
    box-shadow var(--yp-motion-overlay) var(--yp-ease-standard),
    border-color var(--yp-motion-overlay) var(--yp-ease-standard);
}

.home-jump:hover {
  border-color: color-mix(in srgb, var(--from) 35%, var(--yp-border-subtle));
  box-shadow: var(--yp-shadow-hover);
  transform: translateY(-2px);
}

.home-jump:active {
  transform: scale(0.98);
}

.home-jump:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 2px;
}

.home-jump--purple {
  --from: var(--yp-label-dark-purple);
  --to: var(--yp-label-lavender);
}

.home-jump--teal {
  --from: var(--yp-status-teal);
  --to: var(--yp-label-aquamarine);
}

.home-jump--red {
  --from: var(--yp-label-red);
  --to: var(--yp-label-sunset);
}

.home-jump--green {
  --from: var(--yp-status-green);
  --to: var(--yp-label-bright-green);
}

.home-jump--amber {
  --from: var(--yp-label-dark-orange);
  --to: var(--yp-label-egg-yolk);
}

.home-jump__icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--from), var(--to));
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, var(--yp-status-blue-foreground) 35%, transparent),
    0 6px 14px -4px color-mix(in srgb, var(--from) 55%, transparent);
  color: var(--yp-status-blue-foreground);
  font-size: 18px;
}

.home-jump__arrow {
  position: absolute;
  top: var(--yp-space-4);
  right: var(--yp-space-4);
  color: var(--yp-text-muted);
  opacity: 0;
  translate: -4px 4px;
  transition:
    opacity var(--yp-motion-overlay) var(--yp-ease-standard),
    translate var(--yp-motion-overlay) var(--yp-ease-standard);
}

.home-jump:hover .home-jump__arrow,
.home-jump:focus-visible .home-jump__arrow {
  opacity: 1;
  translate: none;
}

.home-jump strong {
  font-size: 14px;
  font-weight: 600;
}

.home-jump__meta {
  display: flex;
  align-items: flex-end;
  gap: var(--yp-space-2);
  color: var(--yp-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.home-spark {
  display: inline-flex;
  align-items: flex-end;
  gap: 3px;
  height: 18px;
}

.home-spark i {
  width: 6px;
  border-radius: 2px;
  background: linear-gradient(var(--yp-label-aquamarine), var(--yp-label-bright-blue));
  opacity: 0.55;
  transition: height var(--yp-motion-overlay) var(--yp-ease-standard);
}

.home-spark i.is-today {
  opacity: 1;
}

.home-columns {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  align-items: start;
  gap: var(--yp-space-4);
}

.home-panel {
  min-width: 0;
  padding: var(--yp-space-4) var(--yp-space-5);
  border: 1px solid color-mix(in srgb, var(--yp-border-subtle) 80%, transparent);
  border-radius: var(--yp-radius-xl);
  background: color-mix(in srgb, var(--yp-bg-surface) 76%, transparent);
  box-shadow: var(--yp-shadow-card);
  backdrop-filter: blur(14px) saturate(1.3);
  animation: home-rise 360ms var(--yp-ease-standard) backwards;
  animation-delay: 320ms;
}

.home-panel--projects {
  animation-delay: 380ms;
}

.home-panel__head {
  display: flex;
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
  background: linear-gradient(135deg, var(--mark), color-mix(in srgb, var(--mark) 70%, var(--yp-bg-surface)));
  color: var(--yp-status-blue-foreground);
  font-family: var(--yp-font-heading);
  font-size: 14px;
  font-weight: 600;
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

.home-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--yp-link);
  font: inherit;
  font-size: 12px;
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
  .home-jump,
  .home-panel {
    animation: none;
  }

  .home-jump:hover,
  .home-jump:active {
    transform: none;
  }
}

@media (max-width: 1099.98px) {
  .home-columns {
    grid-template-columns: minmax(0, 1fr);
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
