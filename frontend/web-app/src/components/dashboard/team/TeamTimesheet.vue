<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElDatePicker, ElMessage, ElTable, ElTableColumn, ElTooltip } from 'element-plus'
import { ArrowLeft, ArrowRight, Download } from '@element-plus/icons-vue'
import type { TeamTimesheet } from '@yumpoo/api-client'
import YpAssignee from '../../yp/YpAssignee.vue'
import YpSegmented from '../../yp/YpSegmented.vue'
import { formatTimestamp } from '../../../design-system/dates'
import { exportFileName, saveExportFile, XLSX_TYPE } from '../exportFile'
import { buildTimesheet, formatHours, hours, isWeekend, MAX_RANGE_DAYS, periodFor, periodLabel, periodOptions, rangeDays, shiftPeriod, todayIn, weekday,
  type Period, type PeriodKind, type TimesheetItemRow, type TimesheetRow } from './teamDashboardModel'

const props = defineProps<{ sheet?: TeamTimesheet | undefined; loading: boolean; error: string; timezone: string; scopeLabel: string }>()
const period = defineModel<Period>('period', { required: true })
const emit = defineEmits<{ retry: [] }>()
const hideEmpty = ref(false), exporting = ref(false)
const model = computed(() => props.sheet ? buildTimesheet(props.sheet) : { rows: [], dayTotals: {}, totalMs: 0 })
const days = computed(() => props.sheet ? rangeDays(props.sheet.from.toISOString().slice(0, 10), props.sheet.to.toISOString().slice(0, 10)) : [])
const rows = computed(() => hideEmpty.value ? model.value.rows.filter(row => row.totalMs > 0) : model.value.rows)
const custom = computed<[string, string]>(() => [period.value.from, period.value.to])

function changeKind(kind: PeriodKind) {
  if (kind === 'CUSTOM') period.value = { ...period.value, kind }
  else period.value = periodFor(kind, todayIn(props.timezone))
}
function changeCustom(value: [string, string] | null) {
  if (!value) return
  if (rangeDays(value[0], value[1]).length > MAX_RANGE_DAYS) { ElMessage.warning(`统计区间最多 ${MAX_RANGE_DAYS} 天`); return }
  period.value = { kind: 'CUSTOM', from: value[0], to: value[1] }
}
function heat(ms: number) {
  if (!ms) return undefined
  const mix = ms >= 8 * 3_600_000 ? 38 : ms >= 4 * 3_600_000 ? 26 : ms >= 2 * 3_600_000 ? 16 : 8
  return { background: `color-mix(in srgb, var(--yp-action-primary) ${mix}%, transparent)` }
}
function summary({ columns }: { columns: { property: string }[] }) {
  return columns.map((column, index) => index === 0 ? '合计' : column.property === 'total' ? formatHours(model.value.totalMs) : formatHours(model.value.dayTotals[column.property] ?? 0))
}
async function exportExcel() {
  if (!props.sheet || exporting.value) return
  exporting.value = true
  try {
    const { buildTimesheetWorkbook } = await import('./teamTimesheetExcel')
    const blob = await buildTimesheetWorkbook({ rows: rows.value, days: days.value, timezone: props.sheet.timezone, scopeLabel: props.scopeLabel,
      exportedAt: formatTimestamp(new Date(), props.timezone) })
    if (await saveExportFile(blob, exportFileName(`成员工时 ${days.value[0]}至${days.value[days.value.length - 1]}`, 'xlsx'), XLSX_TYPE)) ElMessage.success('已导出 Excel')
  } catch (reason) { ElMessage.error(reason instanceof Error && reason.message ? reason.message : '导出失败，请重试') }
  finally { exporting.value = false }
}
const isMember = (row: TimesheetRow | TimesheetItemRow): row is TimesheetRow => 'userId' in row
const byTotal = (a: TimesheetRow, b: TimesheetRow) => a.totalMs - b.totalMs
</script>

<template>
  <section
    class="team-panel"
    aria-labelledby="team-timesheet-title"
  >
    <header class="team-panel__header">
      <h2 id="team-timesheet-title">
        成员工时
      </h2>
      <div class="team-panel__controls">
        <YpSegmented
          :model-value="period.kind"
          :options="periodOptions"
          label="统计周期"
          @update:model-value="changeKind"
        />
        <el-date-picker
          v-if="period.kind === 'CUSTOM'"
          :model-value="custom"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          :clearable="false"
          class="team-range"
          @update:model-value="changeCustom"
        />
        <div
          v-else
          class="team-period"
        >
          <el-button
            :icon="ArrowLeft"
            text
            aria-label="上一周期"
            @click="period = shiftPeriod(period, -1)"
          /><span>{{ periodLabel(period) }}</span><el-button
            :icon="ArrowRight"
            text
            aria-label="下一周期"
            @click="period = shiftPeriod(period, 1)"
          />
        </div>
      </div>
      <div class="team-panel__actions">
        <el-checkbox v-model="hideEmpty">
          隐藏无工时成员
        </el-checkbox>
        <el-button
          :icon="Download"
          :loading="exporting"
          :disabled="!sheet || loading"
          @click="exportExcel"
        >
          导出 Excel
        </el-button>
      </div>
    </header>
    <p class="team-panel__summary">
      合计 {{ formatHours(model.totalMs) || '0.0' }} 小时 · 有工时成员 {{ model.rows.filter(row => row.totalMs > 0).length }} / {{ model.rows.length }}<template v-if="sheet">
        · 统计至 {{ formatTimestamp(sheet.asOf, timezone) }}
      </template>
    </p>
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
    <el-table
      v-loading="loading"
      class="team-timesheet"
      :data="rows"
      row-key="key"
      :tree-props="{ children: 'children' }"
      show-summary
      :summary-method="summary"
      max-height="560"
      empty-text="当前范围没有成员"
    >
      <el-table-column
        prop="name"
        label="成员 / 工作项"
        fixed="left"
        min-width="240"
      >
        <template #default="{ row }">
          <span
            v-if="isMember(row as TimesheetRow | TimesheetItemRow)"
            class="team-member"
          >
            <YpAssignee
              :user-id="row.userId"
              :display-name="row.name"
              size="table"
              tooltip-disabled
            /><small v-if="!row.active">非在职</small>
          </span>
          <el-tooltip
            v-else
            :content="`${row.projectName} · ${row.label}`"
            placement="top-start"
          >
            <span class="team-item"><span>{{ row.label }}</span><small>{{ row.projectName }}</small></span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column
        prop="total"
        label="合计"
        fixed="left"
        width="84"
        align="right"
        sortable
        :sort-method="byTotal"
      >
        <template #default="{ row }">
          <strong>{{ formatHours(row.totalMs) }}</strong>
        </template>
      </el-table-column>
      <el-table-column
        v-for="day in days"
        :key="day"
        :prop="day"
        width="58"
        align="right"
        :class-name="isWeekend(day) ? 'team-day--weekend' : ''"
      >
        <template #header>
          <span class="team-day-head">{{ day.slice(5).replace('-', '/') }}<small>{{ weekday(day) }}</small></span>
        </template>
        <template #default="{ row }">
          <span
            class="team-cell"
            :style="heat(row.days[day] ?? 0)"
            :title="row.days[day] ? `${hours(row.days[day])} 小时` : undefined"
          >{{ formatHours(row.days[day] ?? 0) }}</span>
        </template>
      </el-table-column>
    </el-table>
    <p class="team-panel__note">
      按公司时区（{{ timezone }}）切分自然日，跨日计时按实际时长拆分；运行中的计时统计到刷新时刻。
    </p>
  </section>
</template>
