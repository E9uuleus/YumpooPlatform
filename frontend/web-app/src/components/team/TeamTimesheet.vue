<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onUpdated, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElMessage } from 'element-plus'
import { Download, Upload } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import type { TeamTimesheet } from '@yumpoo/api-client'
import DayBreakdownPopover from './DayBreakdownPopover.vue'
import TeamPeriodPicker from './TeamPeriodPicker.vue'
import YpAssignee from '../yp/YpAssignee.vue'
import { formatTimestamp } from '../../design-system/dates'
import { exportFileName, saveExportFile, XLSX_TYPE } from '../dashboard/exportFile'
import { MIGRATED_BADGE, MIGRATED_LEGEND } from '../timer/timeCategories'
import { buildTimesheet, dayBreakdown, heatMix, migratedDays, restrictTimesheet, timesheetColors, shortHours, timesheetColumns, todayIn, weekday,
  type Period, type TimesheetRow } from './teamDashboardModel'
import { staggerIn, useTweenedNumber, type StaggerFrom } from './teamMotion'

const props = defineProps<{ sheet?: TeamTimesheet | undefined; loading: boolean; error: string; timezone: string; overtime?: boolean }>()
const period = defineModel<Period>('period', { required: true })
const emit = defineEmits<{ retry: [] }>()
const router = useRouter()
const hideEmpty = ref(false), exporting = ref(false)
const picker = ref<InstanceType<typeof TeamPeriodPicker>>()
const grid = ref<HTMLTableElement>(), sheetBox = ref<HTMLElement>(), selected = ref(new Set<string>()), sortTotal = ref<'desc' | 'asc'>()
const active = ref<{ row: TimesheetRow; day: string; anchor: HTMLElement }>()
const columns = computed(() => props.sheet ? timesheetColumns(props.sheet, props.overtime) : [])
const days = computed(() => columns.value.map(column => column.day))
const fullModel = computed(() => props.sheet ? buildTimesheet(props.sheet) : { rows: [], dayTotals: {}, totalMs: 0 })
const model = computed(() => props.overtime ? restrictTimesheet(fullModel.value, days.value) : fullModel.value)
const holidayMs = computed(() => columns.value.filter(column => column.holiday).reduce((sum, column) => sum + (model.value.dayTotals[column.day] ?? 0), 0))
const today = computed(() => todayIn(props.timezone))
const colors = computed(() => timesheetColors(props.sheet))
const migrated = computed(() => new Map(model.value.rows.map(row => [row.key, migratedDays(row)])))
const hasMigrated = computed(() => [...migrated.value.values()].some(days => Object.keys(days).length > 0))
const migratedMs = (row: TimesheetRow, day: string) => migrated.value.get(row.key)?.[day] ?? 0
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
function setEnterDirection(direction: -1 | 1) { enterFrom = direction < 0 ? 'left' : 'right' }
function heat(ms: number) {
  const mix = heatMix(ms)
  return { background: `color-mix(in srgb, var(--yp-label-bright-blue) ${mix}%, transparent)` }
}
function toggleAll(checked: boolean) { selected.value = new Set(checked ? rows.value.map(row => row.userId) : []) }
function toggleRow(userId: string, checked: boolean) {
  const next = new Set(selected.value)
  if (checked) next.add(userId)
  else next.delete(userId)
  selected.value = next
}
function openHoursImport() { void router.push({ name: 'team-hours-import' }) }
function cycleSort(){ sortTotal.value = !sortTotal.value ? 'desc' : sortTotal.value === 'desc' ? 'asc' : undefined }
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
  if (target instanceof Element && (active.value?.anchor.contains(target) || target.closest('.day-breakdown-popover'))) return
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
function closePopovers() { active.value = undefined; picker.value?.close() }
defineExpose({ closePopovers })

async function exportExcel() {
  if (!props.sheet || exporting.value) return
  const picked = checkedRows.value, exported = picked.length ? picked : rows.value
  exporting.value = true
  try {
    const { buildTimesheetWorkbook } = await import('./teamTimesheetExcel')
    const title = props.overtime ? '成员加班' : '成员工时'
    const blob = await buildTimesheetWorkbook({ rows: exported, days: days.value, timezone: props.sheet.timezone, scopeLabel: '全部项目', title, nonWorkdays: new Set(columns.value.filter(column => column.rest).map(column => column.day)),
      memberScopeLabel: picked.length ? picked.map(row => row.name).join('、') : hideEmpty.value ? props.overtime ? '有加班成员' : '有工时成员' : '全部成员', exportedAt: formatTimestamp(new Date(), props.timezone) })
    if (await saveExportFile(blob, exportFileName(`${title} ${days.value[0]}至${days.value[days.value.length - 1]}`, 'xlsx'), XLSX_TYPE)) ElMessage.success('已导出 Excel')
  } catch (reason) { ElMessage.error(reason instanceof Error && reason.message ? reason.message : '导出失败，请重试') }
  finally { exporting.value = false }
}
</script>

<template>
  <section
    class="team-view team-view--timesheet"
    :aria-label="overtime ? '加班统计' : '成员工时'"
  >
    <div class="dashboard-toolbar team-toolbar">
      <slot name="switch" />
      <span
        class="toolbar-divider"
        aria-hidden="true"
      />
      <TeamPeriodPicker
        ref="picker"
        v-model:period="period"
        data-tour="team-period"
        :timezone="timezone"
        @step="setEnterDirection"
      />
      <div
        class="team-toolbar__end"
        data-tour="team-export"
      >
        <el-checkbox v-model="hideEmpty">
          {{ overtime ? '隐藏无加班成员' : '隐藏无工时成员' }}
        </el-checkbox>
        <el-button
          v-if="!overtime"
          :icon="Upload"
          @click="openHoursImport"
        >
          迁入工时
        </el-button>
        <el-button
          :icon="Download"
          :loading="exporting"
          :disabled="!sheet || loading || !days.length"
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
        {{ overtime ? '加班合计' : '合计' }} <strong>{{ shortHours(shownTotalMs) }}</strong> 小时<template v-if="overtime">（休息日 {{ shortHours(model.totalMs - holidayMs) }} · 节假日 {{ shortHours(holidayMs) }}）</template> · {{ overtime ? '有加班成员' : '有工时成员' }} <strong>{{ Math.round(shownMembers) }}</strong> / {{ model.rows.length }}<template v-if="sheet">
          · 统计至 {{ formatTimestamp(sheet.asOf, timezone) }}
        </template>
      </p>
      <div
        ref="sheetBox"
        class="team-sheet"
        data-tour="team-grid"
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
                :class="{ 'is-weekend': column.rest, 'is-today': column.day === today, 'is-holiday': column.holiday }"
              >
                <b>{{ column.label }}</b><small>{{ column.weekday }}</small>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in overtime && !days.length ? [] : rows"
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
                :class="{ 'is-weekend': column.rest }"
              >
                <button
                  v-if="row.days[column.day]"
                  type="button"
                  class="team-chip"
                  :class="{ 'is-active': isActive(row, column.day) }"
                  :style="heat(row.days[column.day] ?? 0)"
                  :aria-label="`${row.name} ${column.day} ${shortHours(row.days[column.day] ?? 0)} 小时${migratedMs(row, column.day) ? `，含迁入工时 ${shortHours(migratedMs(row, column.day))} 小时` : ''}，查看工作项`"
                  @click="openDay(row, column.day, $event)"
                >
                  {{ shortHours(row.days[column.day] ?? 0) }}<i
                    v-if="migratedMs(row, column.day)"
                    class="team-chip__migrated"
                    aria-hidden="true"
                  >{{ MIGRATED_BADGE }}</i>
                </button>
                <span
                  v-else
                  class="team-zero"
                >0</span>
              </td>
            </tr>
            <tr v-if="(!rows.length || (overtime && !days.length)) && !loading">
              <td
                :colspan="columns.length + 3"
                class="team-empty"
              >
                {{ overtime ? days.length ? '当前周期没有成员加班' : '当前周期没有休息日或节假日' : model.rows.length ? '当前周期没有成员记录工时' : '当前没有成员' }}
              </td>
            </tr>
          </tbody>
          <tfoot v-if="rows.length && (!overtime || days.length)">
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
      <p
        v-if="hasMigrated"
        class="team-note"
      >
        {{ MIGRATED_LEGEND }}
      </p>
      <p class="team-note">
        <template v-if="overtime">仅统计公司工作日历中休息日与法定节假日的工时，日历在「公司管理 → 工作日历」维护。点击工时数字查看当日工作项。</template>
        <template v-else>按公司时区（{{ timezone }}）切分自然日，跨日计时按实际时长拆分；运行中的计时统计到刷新时刻。点击工时数字查看当日工作项。</template>
      </p>
    </div>
    <DayBreakdownPopover
      v-if="active"
      :key="`${active.row.key}:${active.day}`"
      :anchor="active.anchor"
      :title="`${active.row.name} · ${active.day.slice(5)} 周${weekday(active.day)}`"
      :total-ms="active.row.days[active.day] ?? 0"
      :projects="breakdown"
      :colors="colors"
    />
  </section>
</template>
