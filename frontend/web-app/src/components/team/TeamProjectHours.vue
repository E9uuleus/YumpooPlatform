<script setup lang="ts">
import YpNotice from '../yp/YpNotice.vue'
import { computed, ref } from 'vue'
import { ElButton, ElIcon, ElMessage } from 'element-plus'
import { ArrowDown, ArrowRight, Download } from '@element-plus/icons-vue'
import type { TeamTimesheet } from '@yumpoo/api-client'
import YpAssignee from '../yp/YpAssignee.vue'
import TeamPeriodPicker from './TeamPeriodPicker.vue'
import { formatTimestamp } from '../../design-system/dates'
import { exportFileName, saveExportFile, XLSX_TYPE } from '../dashboard/exportFile'
import { categoryOf, NON_PROJECT_KEY } from '../timer/timeCategories'
import { buildProjectHours, heatMix, shortHours, timesheetColors, timesheetColumns, todayIn, type Period } from './teamDashboardModel'

/** Company-wide hours per project from the same team timesheet; a project expands into its members and linked categories. */
const props = defineProps<{ sheet?: TeamTimesheet | undefined; loading: boolean; error: string; timezone: string }>()
const period = defineModel<Period>('period', { required: true })
const emit = defineEmits<{ retry: [] }>()
const expanded = ref(new Set<string>()), exporting = ref(false)
const columns = computed(() => props.sheet ? timesheetColumns(props.sheet) : [])
const model = computed(() => props.sheet ? buildProjectHours(props.sheet) : { rows: [], dayTotals: {}, totalMs: 0 })
const projectCount = computed(() => model.value.rows.filter(row => row.projectId !== NON_PROJECT_KEY).length)
const colors = computed(() => timesheetColors(props.sheet))
const today = computed(() => todayIn(props.timezone))
const heat = (ms: number) => ({ background: `color-mix(in srgb, var(--team-row-color) ${heatMix(ms)}%, transparent)` })
function toggle(projectId: string) {
  const next = new Set(expanded.value)
  if (!next.delete(projectId)) next.add(projectId)
  expanded.value = next
}
async function exportExcel() {
  if (!props.sheet || exporting.value) return
  exporting.value = true
  try {
    const days = columns.value.map(column => column.day)
    const { buildProjectHoursWorkbook } = await import('./teamProjectHoursExcel')
    const blob = await buildProjectHoursWorkbook({ rows: model.value.rows, days, timezone: props.sheet.timezone, exportedAt: formatTimestamp(new Date(), props.timezone) })
    if (await saveExportFile(blob, exportFileName(`项目工时 ${days[0]}至${days[days.length - 1]}`, 'xlsx'), XLSX_TYPE)) ElMessage.success('已导出 Excel')
  } catch (reason) { ElMessage.error(reason instanceof Error && reason.message ? reason.message : '导出失败，请重试') }
  finally { exporting.value = false }
}
</script>

<template>
  <section
    class="team-view team-view--projects"
    aria-label="项目工时"
  >
    <div class="dashboard-toolbar team-toolbar">
      <TeamPeriodPicker
        v-model:period="period"
        :timezone="timezone"
      />
      <div class="team-toolbar__end">
        <el-button
          :icon="Download"
          :loading="exporting"
          :disabled="!sheet || loading || !model.rows.length"
          @click="exportExcel"
        >
          导出 Excel
        </el-button>
      </div>
    </div>
    <div
      v-loading="loading"
      class="team-body"
    >
      <yp-notice
        v-if="error"
        tone="danger"
      >
        {{ sheet ? `刷新失败，当前显示上次数据。${error}` : error }}

        <template #actions>
          <el-button
            text
            @click="emit('retry')"
          >
            重试
          </el-button>
        </template>
      </yp-notice>
      <p class="team-summary">
        合计 <strong>{{ shortHours(model.totalMs) }}</strong> 小时 · 有工时项目 <strong>{{ projectCount }}</strong><template v-if="sheet">
          · 统计至 {{ formatTimestamp(sheet.asOf, timezone) }}
        </template>
      </p>
      <div class="team-sheet">
        <table
          class="team-grid team-grid--projects"
          :style="{ minWidth: `${276 + columns.length * 64}px` }"
        >
          <colgroup>
            <col class="team-col-check"><col class="team-col-name"><col class="team-col-total">
            <col
              v-for="column in columns"
              :key="column.day"
            >
          </colgroup>
          <thead>
            <tr>
              <th class="team-sticky team-sticky--check" />
              <th class="team-sticky team-sticky--name">
                项目
              </th>
              <th class="team-sticky team-sticky--total">
                合计
              </th>
              <th
                v-for="column in columns"
                :key="column.day"
                class="team-day-head"
                :class="{ 'is-weekend': column.rest, 'is-today': column.day === today, 'is-holiday': column.holiday }"
              >
                <b>{{ column.label }}</b><small>{{ column.weekday }}</small>
              </th>
            </tr>
          </thead>
          <tbody>
            <template
              v-for="row in model.rows"
              :key="row.key"
            >
              <tr :style="{ '--team-row-color': colors.get(row.projectId) }">
                <td class="team-sticky team-sticky--check">
                  <button
                    type="button"
                    class="team-expand"
                    :aria-expanded="expanded.has(row.projectId)"
                    :aria-label="`${expanded.has(row.projectId) ? '收起' : '展开'}${row.name}`"
                    @click="toggle(row.projectId)"
                  >
                    <el-icon><ArrowDown v-if="expanded.has(row.projectId)" /><ArrowRight v-else /></el-icon>
                  </button>
                </td>
                <td class="team-sticky team-sticky--name">
                  <span class="team-project"><i
                    class="team-project__mark"
                    aria-hidden="true"
                  /><span>{{ row.name }}</span></span>
                </td>
                <td class="team-sticky team-sticky--total">
                  {{ shortHours(row.totalMs) }}
                </td>
                <td
                  v-for="column in columns"
                  :key="column.day"
                  :class="{ 'is-weekend': column.rest }"
                >
                  <span
                    v-if="row.days[column.day]"
                    class="team-chip"
                    :style="heat(row.days[column.day]!)"
                  >{{ shortHours(row.days[column.day]!) }}</span>
                  <span
                    v-else
                    class="team-zero"
                  >0</span>
                </td>
              </tr>
              <template v-if="expanded.has(row.projectId)">
                <tr
                  v-for="child in row.children"
                  :key="`${row.key}:${child.key}`"
                  class="team-project-child"
                  :style="{ '--team-row-color': colors.get(row.projectId) }"
                >
                  <td class="team-sticky team-sticky--check" />
                  <td class="team-sticky team-sticky--name">
                    <YpAssignee
                      v-if="child.userId"
                      :user-id="child.userId"
                      :display-name="child.label"
                      size="table"
                      tooltip-disabled
                    />
                    <span
                      v-else
                      class="team-project-category"
                    ><component
                      :is="categoryOf(child.key)?.icon"
                      aria-hidden="true"
                    />{{ child.label }}</span>
                  </td>
                  <td class="team-sticky team-sticky--total">
                    {{ shortHours(child.totalMs) }}
                  </td>
                  <td
                    v-for="column in columns"
                    :key="column.day"
                    :class="{ 'is-weekend': column.rest }"
                  >
                    <span
                      v-if="child.days[column.day]"
                      class="team-chip"
                      :style="heat(child.days[column.day]!)"
                    >{{ shortHours(child.days[column.day]!) }}</span>
                    <span
                      v-else
                      class="team-zero"
                    >0</span>
                  </td>
                </tr>
              </template>
            </template>
            <tr v-if="!model.rows.length && !loading">
              <td
                :colspan="columns.length + 3"
                class="team-empty"
              >
                当前周期没有记录工时
              </td>
            </tr>
          </tbody>
          <tfoot v-if="model.rows.length">
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
              >
                {{ model.dayTotals[column.day] ? shortHours(model.dayTotals[column.day]!) : '' }}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
      <p class="team-note">
        按公司时区（{{ timezone }}）切分自然日，并行计时按项数平均计入。展开项目可查看成员，以及关联到该项目的出差、学习、商务、会议；未关联项目的这类工时列在最后。
      </p>
    </div>
  </section>
</template>
