<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElIcon, ElPopover } from 'element-plus'
import { ArrowLeft, ArrowRight, Calendar } from '@element-plus/icons-vue'
import type { TeamTimesheet } from '@yumpoo/api-client'
import { myWorkApi } from '../../../api/client'
import { problemMessage, toApiProblem } from '../../../api/problems'
import { useSession } from '../../../composables/useSession'
import { onTimeTrackingChanged, useTimeTracker } from '../../../composables/useTimeTracker'
import '../../projects/workItemAccentBar.css'
import { buildTimesheet, dayBreakdown, hoursMinutes, projectColors, shortHours, todayIn, toApiDate, weekday } from '../team/teamDashboardModel'
import { useTweenedNumber } from '../team/teamMotion'
import { daySegments, monthGrid, monthLabel, monthProjects, monthStats, runningExtraMs, shiftMonth, STANDARD_DAY_MS, weekdayLabels,
  type CalendarCell } from './myTimeModel'

const session = useSession(), tracker = useTimeTracker()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const today = computed(() => todayIn(timezone.value, new Date(tracker.now.value)))
const thisMonth = computed(() => today.value.slice(0, 7))
const month = ref(thisMonth.value), direction = ref<'next' | 'prev'>('next')
const sheet = ref<TeamTimesheet>(), sheetMonth = ref(''), loading = ref(false), error = ref('')
const active = ref<{ date: string; anchor: HTMLElement }>()
const cells = computed(() => monthGrid(month.value, today.value))
// Only the sheet fetched for the visible month is shown, so a new month starts empty and its cells fill in on arrival.
const row = computed(() => sheet.value && sheetMonth.value === month.value ? buildTimesheet(sheet.value).rows[0] : undefined)
const running = computed(() => tracker.current.value?.session ?? undefined)
const days = computed(() => {
  const totals: Record<string, number> = { ...row.value?.days }
  const extra = row.value ? runningExtraMs(sheet.value?.asOf, running.value?.startedAt, tracker.now.value) : 0
  if (extra) totals[today.value] = (totals[today.value] ?? 0) + extra
  return totals
})
const segments = computed(() => daySegments(row.value))
const colors = computed(() => projectColors(sheet.value?.workItems.map(item => item.projectId) ?? []))
const legend = computed(() => monthProjects(row.value, month.value))
const stats = computed(() => monthStats(days.value, month.value))
const weekTotals = computed(() => Array.from({ length: 6 }, (_, index) => cells.value.slice(index * 7, index * 7 + 7)
  .reduce((sum, cell) => sum + (days.value[cell.date] ?? 0), 0)))
const breakdown = computed(() => active.value && row.value ? dayBreakdown(row.value, active.value.date) : [])
const shownTotal = useTweenedNumber(() => stats.value.totalMs)
const shownAverage = useTweenedNumber(() => stats.value.averageMs)
const shownDays = useTweenedNumber(() => stats.value.recordedDays)
const shownToday = useTweenedNumber(() => days.value[today.value] ?? 0)

const level = (ms: number) => Math.min(1, ms / STANDARD_DAY_MS)
const segmentWidth = (ms: number, dayMs: number) => `${(ms / Math.max(dayMs, STANDARD_DAY_MS)) * 100}%`
const dayLabel = (date: string) => `${Number(date.slice(5, 7))}月${Number(date.slice(8))}日 周${weekday(date)}`

let token = 0
async function load(): Promise<void> {
  const current = ++token, target = month.value, grid = cells.value
  loading.value = true
  error.value = ''
  try {
    const result = await myWorkApi.getMyTimesheet({ from: toApiDate(grid[0]!.date), to: toApiDate(grid[grid.length - 1]!.date) })
    if (current === token) { sheet.value = result; sheetMonth.value = target }
  } catch (reason) {
    if (current === token) error.value = problemMessage(await toApiProblem(reason))
  } finally {
    if (current === token) loading.value = false
  }
}
function step(delta: 1 | -1) {
  direction.value = delta > 0 ? 'next' : 'prev'
  month.value = shiftMonth(month.value, delta)
}
function goThisMonth() {
  direction.value = month.value < thisMonth.value ? 'next' : 'prev'
  month.value = thisMonth.value
}

function openDay(cell: CalendarCell, event: MouseEvent) {
  const anchor = event.currentTarget as HTMLElement
  active.value = active.value?.anchor === anchor ? undefined : { date: cell.date, anchor }
}
function closeDayOnOutside(event: PointerEvent) {
  const target = event.target
  if (target instanceof Element && (active.value?.anchor.contains(target) || target.closest('.my-time-day-popover'))) return
  active.value = undefined
}
function closeDayOnEscape(event: KeyboardEvent) { if (event.key === 'Escape') active.value = undefined }
function stopListening() {
  document.removeEventListener('pointerdown', closeDayOnOutside, true)
  document.removeEventListener('keydown', closeDayOnEscape)
}
watch(() => Boolean(active.value), open => {
  if (!open) { stopListening(); return }
  document.addEventListener('pointerdown', closeDayOnOutside, true)
  document.addEventListener('keydown', closeDayOnEscape)
})
watch(month, () => { active.value = undefined; void load() })
const stopTracking = onTimeTrackingChanged(() => void load())
onBeforeUnmount(() => { ++token; stopListening(); stopTracking() })
void load()
defineExpose({ refresh: load, loading })
</script>

<template>
  <section
    class="my-time"
    aria-label="我的工时"
  >
    <div class="dashboard-toolbar my-time__toolbar">
      <div class="my-time__period">
        <el-button
          :icon="ArrowLeft"
          text
          aria-label="上个月"
          @click="step(-1)"
        />
        <span class="my-time__month">
          <el-icon aria-hidden="true"><Calendar /></el-icon>
          <span class="my-time__month-text"><Transition :name="`my-time-label-${direction}`">
            <span :key="month">{{ monthLabel(month) }}</span>
          </Transition></span>
        </span>
        <el-button
          :icon="ArrowRight"
          text
          aria-label="下个月"
          :disabled="month >= thisMonth"
          @click="step(1)"
        />
      </div>
      <el-button
        :disabled="month === thisMonth"
        @click="goThisMonth"
      >
        本月
      </el-button>
      <span class="my-time__note">按公司时区（{{ timezone }}）切分自然日，跨日计时按实际时长拆分，运行中的计时实时累加。</span>
    </div>
    <div
      v-loading="loading && !row"
      class="my-time__body"
    >
      <el-alert
        v-if="error"
        :title="row ? `刷新失败，当前显示上次数据。${error}` : error"
        type="error"
        :closable="false"
      >
        <el-button
          text
          @click="load"
        >
          重试
        </el-button>
      </el-alert>
      <div class="my-time__stats">
        <div class="my-time__stat">
          <small>本月合计</small><strong>{{ shortHours(shownTotal) }}</strong><span>小时</span>
        </div>
        <div class="my-time__stat">
          <small>日均（按有记录的天）</small><strong>{{ shortHours(shownAverage) }}</strong><span>小时</span>
        </div>
        <div class="my-time__stat">
          <small>记录天数</small><strong>{{ Math.round(shownDays) }}</strong><span>天</span>
        </div>
        <div class="my-time__stat">
          <small>今日<template v-if="running"><i
            class="my-time__live"
            aria-hidden="true"
          />计时中</template></small><strong>{{ hoursMinutes(shownToday) }}</strong>
        </div>
      </div>
      <div class="my-time__board">
        <div
          class="my-time__weekdays"
          aria-hidden="true"
        >
          <span
            v-for="label in weekdayLabels"
            :key="label"
          >周{{ label }}</span><span>周计</span>
        </div>
        <Transition
          :name="`my-time-grid-${direction}`"
          mode="out-in"
        >
          <div
            :key="month"
            class="my-time__grid"
            role="group"
            :aria-label="`${monthLabel(month)}每日工时`"
          >
            <template
              v-for="cell in cells"
              :key="cell.date"
            >
              <button
                type="button"
                class="my-time-cell"
                :class="{
                  'is-outside': !cell.inMonth,
                  'is-weekend': cell.weekend,
                  'is-today': cell.today,
                  'is-full': (days[cell.date] ?? 0) >= STANDARD_DAY_MS,
                  'is-active': active?.date === cell.date,
                }"
                :style="{ '--d': cell.row + cell.column, '--level': level(days[cell.date] ?? 0) }"
                :disabled="!days[cell.date]"
                :aria-label="days[cell.date] ? `${dayLabel(cell.date)} ${shortHours(days[cell.date] ?? 0)} 小时，查看明细` : dayLabel(cell.date)"
                @click="openDay(cell, $event)"
              >
                <span
                  v-if="days[cell.date]"
                  class="my-time-cell__level"
                />
                <span class="my-time-cell__date">{{ cell.day }}</span>
                <i
                  v-if="cell.today && running"
                  class="my-time-cell__live"
                  aria-hidden="true"
                />
                <template v-if="days[cell.date]">
                  <span class="my-time-cell__hours">{{ shortHours(days[cell.date] ?? 0) }}<small>h</small></span>
                  <span class="my-time-cell__bar"><i
                    v-for="segment in segments[cell.date] ?? []"
                    :key="segment.projectId"
                    :style="{ width: segmentWidth(segment.ms, days[cell.date] ?? 0), background: colors.get(segment.projectId) }"
                  /></span>
                </template>
              </button>
              <span
                v-if="cell.column === 6"
                class="my-time__week"
              ><b
                v-if="weekTotals[cell.row]"
                :style="{ '--d': cell.row }"
              >{{ shortHours(weekTotals[cell.row] ?? 0) }}</b></span>
            </template>
          </div>
        </Transition>
      </div>
      <ul
        v-if="legend.length"
        class="my-time__legend"
        aria-label="本月项目工时"
      >
        <li
          v-for="project in legend"
          :key="project.projectId"
        >
          <i :style="{ background: colors.get(project.projectId) }" />{{ project.projectName }}<b>{{ hoursMinutes(project.ms) }}</b>
        </li>
      </ul>
    </div>
    <el-popover
      v-if="active"
      :key="active.date"
      :visible="true"
      virtual-triggering
      :virtual-ref="active.anchor"
      placement="bottom"
      :width="340"
      :persistent="false"
      popper-class="my-time-day-popover"
      transition="my-time-pop"
    >
      <header class="my-time-day__head">
        <strong>{{ dayLabel(active.date) }}</strong>
        <span>{{ hoursMinutes(days[active.date] ?? 0) }}</span>
      </header>
      <p
        v-if="!breakdown.length"
        class="my-time-day__empty"
      >
        计时进行中，停止后显示工作项明细。
      </p>
      <section
        v-for="(project, index) in breakdown"
        :key="project.projectId"
        class="my-time-day__project"
        :style="{ '--my-time-project': colors.get(project.projectId), '--i': Math.min(index, 8) }"
      >
        <h4>{{ project.projectName }}<small>{{ hoursMinutes(project.totalMs) }}</small></h4>
        <div
          v-for="(item, order) in project.items"
          :key="item.key"
          class="my-time-day__item work-item-accent-bar"
          :style="{ '--work-item-accent': colors.get(project.projectId), '--j': Math.min(order, 8) }"
        >
          <span class="my-time-day__text"><strong>{{ item.title }}</strong><small>{{ item.itemNo }}</small></span>
          <span class="my-time-day__hours">{{ hoursMinutes(item.ms) }}</span>
        </div>
      </section>
    </el-popover>
  </section>
</template>

<style src="./my-time.css" />
