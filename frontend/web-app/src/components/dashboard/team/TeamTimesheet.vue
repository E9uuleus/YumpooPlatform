<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onUpdated, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElIcon, ElMessage, ElPopover } from 'element-plus'
import { ArrowLeft, ArrowRight, Calendar, Download } from '@element-plus/icons-vue'
import type { TeamTimesheet } from '@yumpoo/api-client'
import TimeCalendar from '../../timer/TimeCalendar.vue'
import YpAssignee from '../../yp/YpAssignee.vue'
import YpSegmented from '../../yp/YpSegmented.vue'
import '../../projects/workItemAccentBar.css'
import { formatTimestamp } from '../../../design-system/dates'
import { exportFileName, saveExportFile, XLSX_TYPE } from '../exportFile'
import { buildTimesheet, dayBreakdown, hoursMinutes, isWeekend, periodFor, periodLabel, periodOptions, projectColors, rangeDays, shiftPeriod, shortHours, todayIn, weekday,
  type Period, type PeriodKind, type TimesheetRow } from './teamDashboardModel'
import { staggerIn, useTweenedNumber, type StaggerFrom } from './teamMotion'

const props = defineProps<{ sheet?: TeamTimesheet | undefined; loading: boolean; error: string; timezone: string }>()
const period = defineModel<Period>('period', { required: true })
const emit = defineEmits<{ retry: [] }>()
const hideEmpty = ref(false), exporting = ref(false), calendarOpen = ref(false)
const grid = ref<HTMLTableElement>(), sheetBox = ref<HTMLElement>(), selected = ref(new Set<string>()), sortTotal = ref<'desc' | 'asc'>()
const active = ref<{ row: TimesheetRow; day: string; anchor: HTMLElement }>()
const model = computed(() => props.sheet ? buildTimesheet(props.sheet) : { rows: [], dayTotals: {}, totalMs: 0 })
const days = computed(() => props.sheet ? rangeDays(props.sheet.from.toISOString().slice(0, 10), props.sheet.to.toISOString().slice(0, 10)) : [])
const columns = computed(() => days.value.map(day => ({ day, label: day.slice(5), weekday: `周${weekday(day)}`, weekend: isWeekend(day) })))
const today = computed(() => todayIn(props.timezone))
const colors = computed(() => projectColors(props.sheet?.workItems.map(item => item.projectId) ?? []))
const rows = computed(() => {
  const visible = hideEmpty.value ? model.value.rows.filter(row => row.totalMs > 0) : model.value.rows
  if (!sortTotal.value) return visible
  const sign = sortTotal.value === 'desc' ? -1 : 1
  return [...visible].sort((a, b) => sign * (a.totalMs - b.totalMs))
})
const checkedRows = computed(() => rows.value.filter(row => selected.value.has(row.userId)))
const allChecked = computed(() => rows.value.length > 0 && checkedRows.value.length === rows.value.length)
const breakdown = computed(() => active.value ? dayBreakdown(active.value.row, active.value.day) : [])
const membersWithTime = computed(() => model.value.rows.filter(row => row.totalMs > 0).length)
const shownTotalMs = useTweenedNumber(() => model.value.totalMs), shownMembers = useTweenedNumber(() => membersWithTime.value)

// Rows slide in from the side the user paged towards once the next sheet arrives.
let enterFrom: StaggerFrom = 'up'
function changeKind(kind: PeriodKind) { period.value = periodFor(kind, todayIn(props.timezone)) }
function step(direction: -1 | 1) { enterFrom = direction < 0 ? 'left' : 'right'; period.value = shiftPeriod(period.value, direction) }
function pickDay(day: string) { calendarOpen.value = false; period.value = periodFor(period.value.kind, day) }
function heat(ms: number) {
  const mix = ms >= 8 * 3_600_000 ? 52 : ms >= 4 * 3_600_000 ? 36 : ms >= 2 * 3_600_000 ? 24 : 14
  return { background: `color-mix(in srgb, var(--yp-label-bright-blue) ${mix}%, transparent)` }
}
function toggleAll(checked: boolean) { selected.value = new Set(checked ? rows.value.map(row => row.userId) : []) }
function toggleRow(userId: string, checked: boolean) {
  const next = new Set(selected.value)
  if (checked) next.add(userId)
  else next.delete(userId)
  selected.value = next
}
function cycleSort() { sortTotal.value = !sortTotal.value ? 'desc' : sortTotal.value === 'desc' ? 'asc' : undefined }
// Column hover is painted on the DOM instead of through reactive state, so crossing columns does not re-render every cell.
let hoverDay: string | undefined
function paintHoverDay() {
  grid.value?.querySelectorAll('.is-col-hover').forEach(cell => cell.classList.remove('is-col-hover'))
  if (hoverDay) grid.value?.querySelectorAll(`[data-day="${hoverDay}"]`).forEach(cell => cell.classList.add('is-col-hover'))
}
function setHoverDay(day: string | undefined) {
  if (day === hoverDay) return
  hoverDay = day
  paintHoverDay()
}
function trackDay(event: MouseEvent) { setHoverDay((event.target as Element).closest<HTMLElement>('[data-day]')?.dataset.day) }
onUpdated(paintHoverDay)
function openDay(row: TimesheetRow, day: string, event: MouseEvent) {
  const anchor = event.currentTarget as HTMLElement
  active.value = active.value?.anchor === anchor ? undefined : { row, day, anchor }
}
const isActive = (row: TimesheetRow, day: string) => active.value?.row.key === row.key && active.value.day === day
function closeDayOnOutside(event: PointerEvent) {
  const target = event.target
  if (target instanceof Element && (active.value?.anchor.contains(target) || target.closest('.team-day-popover'))) return
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
watch(() => props.sheet, async () => {
  active.value = undefined
  const from = enterFrom
  enterFrom = 'up'
  await nextTick()
  if (grid.value?.tBodies[0] && sheetBox.value) staggerIn(grid.value.tBodies[0].rows, sheetBox.value, from)
})
onBeforeUnmount(stopListening)
/** The dashboard hides this view with `v-show`, which would leave teleported popovers floating over the other view. */
function closePopovers() { active.value = undefined; calendarOpen.value = false }
defineExpose({ closePopovers })

async function exportExcel() {
  if (!props.sheet || exporting.value) return
  const picked = checkedRows.value, exported = picked.length ? picked : rows.value
  exporting.value = true
  try {
    const { buildTimesheetWorkbook } = await import('./teamTimesheetExcel')
    const blob = await buildTimesheetWorkbook({ rows: exported, days: days.value, timezone: props.sheet.timezone, scopeLabel: '全部项目',
      memberScopeLabel: picked.length ? picked.map(row => row.name).join('、') : hideEmpty.value ? '有工时成员' : '全部成员', exportedAt: formatTimestamp(new Date(), props.timezone) })
    if (await saveExportFile(blob, exportFileName(`成员工时 ${days.value[0]}至${days.value[days.value.length - 1]}`, 'xlsx'), XLSX_TYPE)) ElMessage.success('已导出 Excel')
  } catch (reason) { ElMessage.error(reason instanceof Error && reason.message ? reason.message : '导出失败，请重试') }
  finally { exporting.value = false }
}
</script>

<template>
  <section
    class="team-view team-view--timesheet"
    aria-label="成员工时"
  >
    <div class="dashboard-toolbar team-toolbar">
      <slot name="switch" />
      <span
        class="toolbar-divider"
        aria-hidden="true"
      />
      <YpSegmented
        :model-value="period.kind"
        :options="periodOptions"
        label="统计周期"
        @update:model-value="changeKind"
      />
      <div class="team-period">
        <el-button
          :icon="ArrowLeft"
          text
          aria-label="上一周期"
          @click="step(-1)"
        />
        <el-popover
          v-model:visible="calendarOpen"
          trigger="click"
          placement="bottom"
          :width="320"
          popper-class="team-calendar-popover"
          transition="team-pop"
        >
          <template #reference>
            <button
              type="button"
              class="team-period__label"
              aria-haspopup="dialog"
              :aria-expanded="calendarOpen"
            >
              <el-icon aria-hidden="true">
                <Calendar />
              </el-icon><span class="team-period__text"><Transition name="team-label">
                <span :key="periodLabel(period)">{{ periodLabel(period) }}</span>
              </Transition></span>
            </button>
          </template>
          <TimeCalendar
            v-if="calendarOpen"
            :model-value="period.from"
            :timezone="timezone"
            expanded
            @update:model-value="pickDay"
          />
        </el-popover>
        <el-button
          :icon="ArrowRight"
          text
          aria-label="下一周期"
          @click="step(1)"
        />
      </div>
      <div class="team-toolbar__end">
        <el-checkbox v-model="hideEmpty">
          隐藏无工时成员
        </el-checkbox>
        <el-button
          :icon="Download"
          :loading="exporting"
          :disabled="!sheet || loading"
          @click="exportExcel"
        >
          {{ checkedRows.length ? `导出所选（${checkedRows.length}）` : '导出 Excel' }}
        </el-button>
      </div>
    </div>
    <div
      v-loading="loading"
      class="team-body"
    >
      <el-alert
        v-if="error"
        :title="sheet ? `刷新失败，当前显示上次数据。${error}` : error"
        type="error"
        :closable="false"
      >
        <el-button
          text
          @click="emit('retry')"
        >
          重试
        </el-button>
      </el-alert>
      <p class="team-summary">
        合计 <strong>{{ shortHours(shownTotalMs) }}</strong> 小时 · 有工时成员 <strong>{{ Math.round(shownMembers) }}</strong> / {{ model.rows.length }}<template v-if="sheet">
          · 统计至 {{ formatTimestamp(sheet.asOf, timezone) }}
        </template>
      </p>
      <div
        ref="sheetBox"
        class="team-sheet"
      >
        <table
          ref="grid"
          class="team-grid"
          :style="{ minWidth: `${276 + columns.length * 64}px` }"
          @mouseover="trackDay"
          @mouseleave="setHoverDay(undefined)"
        >
          <colgroup>
            <col class="team-col-check">
            <col class="team-col-name">
            <col class="team-col-total">
            <col
              v-for="column in columns"
              :key="column.day"
            >
          </colgroup>
          <thead>
            <tr>
              <th class="team-sticky team-sticky--check">
                <el-checkbox
                  :model-value="allChecked"
                  :indeterminate="checkedRows.length > 0 && !allChecked"
                  :disabled="!rows.length"
                  aria-label="选择全部成员"
                  @change="value => toggleAll(value === true)"
                />
              </th>
              <th class="team-sticky team-sticky--name">
                处理人
              </th>
              <th class="team-sticky team-sticky--total">
                <button
                  type="button"
                  class="team-sort"
                  @click="cycleSort"
                >
                  合计{{ sortTotal === 'desc' ? ' ↓' : sortTotal === 'asc' ? ' ↑' : '' }}
                </button>
              </th>
              <th
                v-for="column in columns"
                :key="column.day"
                :data-day="column.day"
                class="team-day-head"
                :class="{ 'is-weekend': column.weekend, 'is-today': column.day === today }"
              >
                <b>{{ column.label }}</b><small>{{ column.weekday }}</small>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in rows"
              :key="row.key"
              :class="{ 'is-selected': selected.has(row.userId) }"
            >
              <td class="team-sticky team-sticky--check">
                <el-checkbox
                  :model-value="selected.has(row.userId)"
                  :aria-label="`选择 ${row.name}`"
                  @change="value => toggleRow(row.userId, value === true)"
                />
              </td>
              <td class="team-sticky team-sticky--name">
                <span class="team-member"><YpAssignee
                  :user-id="row.userId"
                  :display-name="row.name"
                  size="table"
                  tooltip-disabled
                /><small v-if="!row.active">非在职</small></span>
              </td>
              <td class="team-sticky team-sticky--total">
                {{ shortHours(row.totalMs) }}
              </td>
              <td
                v-for="column in columns"
                :key="column.day"
                :data-day="column.day"
                :class="{ 'is-weekend': column.weekend }"
              >
                <button
                  v-if="row.days[column.day]"
                  type="button"
                  class="team-chip"
                  :class="{ 'is-active': isActive(row, column.day) }"
                  :style="heat(row.days[column.day] ?? 0)"
                  :aria-label="`${row.name} ${column.day} ${shortHours(row.days[column.day] ?? 0)} 小时，查看工作项`"
                  @click="openDay(row, column.day, $event)"
                >
                  {{ shortHours(row.days[column.day] ?? 0) }}
                </button>
                <span
                  v-else
                  class="team-zero"
                >0</span>
              </td>
            </tr>
            <tr v-if="!rows.length && !loading">
              <td
                :colspan="columns.length + 3"
                class="team-empty"
              >
                {{ model.rows.length ? '当前周期没有成员记录工时' : '当前没有成员' }}
              </td>
            </tr>
          </tbody>
          <tfoot v-if="rows.length">
            <tr>
              <td class="team-sticky team-sticky--check" />
              <td class="team-sticky team-sticky--name">
                合计
              </td>
              <td class="team-sticky team-sticky--total">
                {{ shortHours(model.totalMs) }}
              </td>
              <td
                v-for="column in columns"
                :key="column.day"
                :data-day="column.day"
              >
                {{ model.dayTotals[column.day] ? shortHours(model.dayTotals[column.day] ?? 0) : '' }}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
      <p class="team-note">
        按公司时区（{{ timezone }}）切分自然日，跨日计时按实际时长拆分；运行中的计时统计到刷新时刻。点击工时数字查看当日工作项。
      </p>
    </div>
    <el-popover
      v-if="active"
      :key="`${active.row.key}:${active.day}`"
      :visible="true"
      virtual-triggering
      :virtual-ref="active.anchor"
      placement="bottom"
      :width="360"
      :persistent="false"
      popper-class="team-day-popover"
      transition="team-pop"
    >
      <header class="team-day__head">
        <strong>{{ active.row.name }} · {{ active.day.slice(5) }} 周{{ weekday(active.day) }}</strong>
        <span>{{ hoursMinutes(active.row.days[active.day] ?? 0) }}</span>
      </header>
      <section
        v-for="(project, index) in breakdown"
        :key="project.projectId"
        class="team-day__project"
        :style="{ '--team-project-color': colors.get(project.projectId), '--i': Math.min(index, 8) }"
      >
        <h4>{{ project.projectName }}<small>{{ hoursMinutes(project.totalMs) }}</small></h4>
        <div
          v-for="(item, row) in project.items"
          :key="item.key"
          class="team-day__item work-item-accent-bar"
          :style="{ '--j': Math.min(row, 8) }"
        >
          <span class="team-day__text"><strong>{{ item.title }}</strong><small>{{ item.itemNo }}</small></span>
          <span class="team-day__hours">{{ hoursMinutes(item.ms) }}</span>
        </div>
      </section>
    </el-popover>
  </section>
</template>
