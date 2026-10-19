<script setup lang="ts">
import { CalendarDaySource, CalendarDayType, readCsrfToken, type WorkCalendarYear, type WorkHoursAlertSetting } from '@yumpoo/api-client'
import { ElAlert, ElButton, ElDialog, ElInput, ElInputNumber, ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { administrationApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import {
  DAY_TYPE_LABELS, buildCalendarTemplate, calendarOverrides, defaultDayType, isRedundant, isWeekendDate,
  parseCalendarWorkbook, yearDates, type CalendarOverride,
} from '../../components/company/workCalendarModel'
import { exportFileName, saveExportFile, XLSX_TYPE } from '../../components/dashboard/exportFile'
import { todayIn, toApiDate, weekday } from '../../components/team/teamDashboardModel'
import YpSegmented from '../../components/yp/YpSegmented.vue'
import { useIdentityAdmin } from '../../composables/useIdentityAdmin'
import { useSession } from '../../composables/useSession'

const session = useSession(), { canWrite } = useIdentityAdmin()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const today = computed(() => todayIn(timezone.value))
const year = ref(Number(today.value.slice(0, 4)))
const data = ref<WorkCalendarYear>()
const alert = ref<WorkHoursAlertSetting>(), alertHours = ref(12), alertSaving = ref(false)
const draft = ref(new Map<string, CalendarOverride>())
const serialize = () => JSON.stringify([...draft.value.values()].sort((a, b) => a.date.localeCompare(b.date)))
const saved = ref('[]'), dirty = computed(() => serialize() !== saved.value)
const loading = ref(false), saving = ref(false), error = ref<ApiProblem>()
const importErrors = ref<string[]>([]), fileInput = ref<HTMLInputElement>()
const dates = computed(() => yearDates(year.value))
const months = computed(() => Array.from({ length: 12 }, (_, index) => {
  const first = `${year.value}-${String(index + 1).padStart(2, '0')}-01`
  return { month: index + 1, offset: (toApiDate(first).getUTCDay() + 6) % 7, days: dates.value.filter(date => date.slice(0, 7) === first.slice(0, 7)) }
}))
const typeOptions = Object.entries(DAY_TYPE_LABELS).map(([value, label]) => ({ value: value as CalendarDayType, label }))
const editing = ref<string>(), dialogOpen = ref(false)
const editType = ref<CalendarDayType>(CalendarDayType.Workday), editNote = ref(''), editHours = ref<number>()
const dialogTitle = computed(() => editing.value ? `${Number(editing.value.slice(5, 7))}月${Number(editing.value.slice(8))}日 周${weekday(editing.value)}` : '')

function accept(next: WorkCalendarYear): void {
  data.value = next
  draft.value = new Map(calendarOverrides(next.days).map(day => [day.date, day]))
  saved.value = serialize()
  importErrors.value = []
}

async function load(): Promise<void> {
  loading.value = true; error.value = undefined
  try {
    const [calendar, setting] = await Promise.all([administrationApi.getWorkCalendarYear({ year: year.value }), administrationApi.getWorkHoursAlert()])
    accept(calendar); alert.value = setting; alertHours.value = setting.dailyThresholdMinutes / 60
  }
  catch (reason) { error.value = await toApiProblem(reason) }
  finally { loading.value = false }
}

async function changeYear(step: number): Promise<void> {
  if (dirty.value) {
    try { await ElMessageBox.confirm('当前年份有未保存的修改，切换后将丢失。', '放弃修改？', { type: 'warning' }) }
    catch { return }
  }
  year.value += step
  await load()
}

function dayType(date: string): CalendarDayType { return draft.value.get(date)?.dayType ?? defaultDayType(date) }
function dayClass(date: string) {
  const type = dayType(date)
  return { 'is-workday': type === 'WORKDAY', 'is-rest': type === 'NON_WORKDAY', 'is-holiday': type === 'HOLIDAY',
    'is-makeup': isWeekendDate(date) && type === 'WORKDAY', 'is-explicit': draft.value.has(date), 'is-today': date === today.value }
}
function badge(date: string): string {
  return draft.value.get(date)?.note || (dayType(date) === 'HOLIDAY' ? '节' : isWeekendDate(date) && dayType(date) === 'WORKDAY' ? '班' : !isWeekendDate(date) && dayType(date) === 'NON_WORKDAY' ? '休' : '')
}
function edit(date: string): void {
  editing.value = date; editType.value = dayType(date); editNote.value = draft.value.get(date)?.note ?? ''
  const minutes = draft.value.get(date)?.standardMinutes
  editHours.value = minutes == null ? undefined : minutes / 60
  dialogOpen.value = true
}
function apply(): void {
  const date = editing.value!
  const override: CalendarOverride = { date, dayType: editType.value,
    standardMinutes: editType.value === 'WORKDAY' && editHours.value != null ? Math.round(editHours.value * 60) : null,
    note: editNote.value.trim() || null, source: CalendarDaySource.Manual }
  if (isRedundant(override)) draft.value.delete(date)
  else draft.value.set(date, override)
  dialogOpen.value = false
}
function resetDay(): void { draft.value.delete(editing.value!); dialogOpen.value = false }

async function save(): Promise<void> {
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新页面后重试。'); return }
  saving.value = true; error.value = undefined
  try {
    accept(await administrationApi.updateWorkCalendarYear({ year: year.value, xXSRFTOKEN: csrf, ifMatch: data.value!.etag,
      workCalendarUpdateRequest: { days: [...draft.value.values()].map(o => ({ date: toApiDate(o.date), dayType: o.dayType, standardMinutes: o.standardMinutes, note: o.note, source: o.source })) } }))
    ElMessage.success('工作日历已保存')
  } catch (reason) { error.value = await toApiProblem(reason) }
  finally { saving.value = false }
}
async function importFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file) return
  try {
    const parsed = await parseCalendarWorkbook(await file.arrayBuffer(), year.value)
    importErrors.value = parsed.errors.slice(0, 5)
    if (parsed.errors.length) return
    draft.value = new Map(parsed.overrides.map(o => [o.date, o]))
    ElMessage.success(`已导入 ${parsed.overrides.length} 个特殊日，检查无误后点击保存`)
  } catch (reason) { error.value = await toApiProblem(reason) }
  finally { input.value = '' }
}
async function download(): Promise<void> {
  try { await saveExportFile(await buildCalendarTemplate([...draft.value.values()]), exportFileName(`${year.value}年工作日历`, 'xlsx'), XLSX_TYPE) }
  catch (reason) { error.value = await toApiProblem(reason) }
}
async function saveAlert(): Promise<void> {
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新页面后重试。'); return }
  alertSaving.value = true; error.value = undefined
  try {
    alert.value = await administrationApi.updateWorkHoursAlert({ xXSRFTOKEN: csrf, ifMatch: alert.value!.etag,
      workHoursAlertUpdateRequest: { dailyThresholdMinutes: Math.round(alertHours.value * 60) } })
    alertHours.value = alert.value.dailyThresholdMinutes / 60
    ElMessage.success('工时异常阈值已保存')
  } catch (reason) { error.value = await toApiProblem(reason) }
  finally { alertSaving.value = false }
}
onMounted(load)
</script>

<template>
  <section
    v-loading="loading"
    class="work-calendar"
  >
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <div class="calendar-toolbar">
      <div class="calendar-year">
        <el-button
          aria-label="上一年"
          :disabled="year <= 2000 || loading || saving"
          @click="changeYear(-1)"
        >
          ‹
        </el-button>
        <strong>{{ year }} 年</strong>
        <el-button
          aria-label="下一年"
          :disabled="year >= 2100 || loading || saving"
          @click="changeYear(1)"
        >
          ›
        </el-button>
      </div>
      <span class="calendar-state">{{ data?.etag === '"0"' ? '尚未设置，当前按默认周末规则' : `已设置 ${draft.size} 个特殊日` }}</span>
      <div class="calendar-actions">
        <el-button
          :disabled="!data || loading || saving"
          @click="download"
        >
          下载模板
        </el-button>
        <el-button
          :disabled="!canWrite || loading || saving"
          @click="fileInput?.click()"
        >
          导入 Excel
        </el-button>
        <el-button
          :disabled="!dirty || saving"
          @click="accept(data!)"
        >
          放弃修改
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!canWrite || !dirty || loading"
          @click="save"
        >
          保存
        </el-button>
        <input
          ref="fileInput"
          type="file"
          accept=".xlsx"
          hidden
          @change="importFile"
        >
      </div>
    </div>
    <el-alert
      v-if="importErrors.length"
      type="error"
      title="导入未完成，草稿未改变"
      :closable="false"
    >
      <ul>
        <li
          v-for="message in importErrors"
          :key="message"
        >
          {{ message }}
        </li>
      </ul>
    </el-alert>
    <div class="calendar-legend">
      <span><i class="is-workday" />工作日</span><span><i class="is-rest" />休息日</span>
      <span><i class="is-holiday" />法定节假日</span><span><i class="is-makeup" />补班</span>
    </div>
    <div
      v-if="data"
      class="calendar-months"
    >
      <article
        v-for="month in months"
        :key="month.month"
        class="calendar-month"
      >
        <h3>{{ month.month }} 月</h3>
        <div class="month-grid">
          <span
            v-for="label in ['一','二','三','四','五','六','日']"
            :key="label"
            class="month-weekday"
          >{{ label }}</span>
          <span
            v-for="space in month.offset"
            :key="`space-${space}`"
          />
          <button
            v-for="date in month.days"
            :key="date"
            type="button"
            class="calendar-day"
            :class="dayClass(date)"
            :disabled="!canWrite || saving"
            :aria-label="`${date} ${DAY_TYPE_LABELS[dayType(date)]} ${badge(date)}`"
            :title="badge(date) || undefined"
            @click="edit(date)"
          >
            <span>{{ Number(date.slice(8)) }}</span><small>{{ badge(date) }}</small>
          </button>
        </div>
      </article>
    </div>
    <article v-if="alert" class="calendar-alert">
      <h3>工时异常提醒</h3>
      <div class="calendar-actions">
        <label for="work-hours-threshold">阈值（小时）</label>
        <el-input-number id="work-hours-threshold" v-model="alertHours" :min="1" :max="24" :step="0.5" :disabled="!canWrite || alertSaving" />
        <el-button type="primary" :disabled="!canWrite" :loading="alertSaving" @click="saveAlert">保存</el-button>
      </div>
      <p>每天检查前一自然日每位成员的总工时（与团队视图同一口径），超过阈值时向全体公司管理员发送站内提醒「该成员工时数据异常请关注」。执行时间与启用在 <router-link :to="{ name: 'company-scheduled-jobs' }">定时任务</router-link> 中设置，默认停用。</p>
    </article>
    <el-dialog
      v-model="dialogOpen"
      :title="dialogTitle"
      width="320px"
    >
      <div class="calendar-editor">
        <yp-segmented
          v-model="editType"
          :options="typeOptions"
          label="日类型"
        />
        <label>名称<el-input
          v-model="editNote"
          :maxlength="30"
          placeholder="可选，最多 30 字"
        /></label>
        <label v-if="editType === 'WORKDAY'">标准工时（小时）
          <el-input-number
            :model-value="editHours ?? null"
            :min="0.5"
            :max="12"
            :step="0.5"
            :placeholder="String((data?.defaultWorkdayMinutes ?? 480) / 60)"
            @update:model-value="editHours = $event"
          />
        </label>
      </div>
      <template #footer>
        <el-button @click="resetDay">
          恢复默认
        </el-button><el-button
          type="primary"
          @click="apply"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.work-calendar { display: grid; gap: var(--yp-space-4); }
.calendar-toolbar,.calendar-year,.calendar-actions,.calendar-legend { display: flex; align-items: center; gap: var(--yp-space-3); flex-wrap: wrap; }
.calendar-year strong { min-width: 76px; text-align: center; }
.calendar-state { flex: 1; color: var(--yp-text-muted); font-size: 13px; }
.calendar-actions { gap: var(--yp-space-2); }
.calendar-actions .el-button + .el-button { margin-left: 0; }
.calendar-legend { color: var(--yp-text-secondary); font-size: 13px; }
.calendar-legend span { display: flex; align-items: center; gap: 6px; }
.calendar-legend i { width: 12px; height: 12px; border: 1px solid var(--yp-border-subtle); border-radius: 3px; }
.calendar-months { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: var(--yp-space-4); }
.calendar-month { padding: var(--yp-space-3); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.calendar-month h3 { margin: 0 0 12px; font-size: 15px; color: var(--yp-text-primary); }
.month-grid { display: grid; grid-template-columns: repeat(7,minmax(0,1fr)); gap: 3px; }
.month-weekday { padding-bottom: 5px; text-align: center; font-size: 12px; color: var(--yp-text-muted); }
.calendar-day { display: grid; grid-template-rows: 18px 14px; align-content: center; gap: 2px; min-width: 0; height: 42px; padding: 2px; border: 1px solid transparent; border-radius: var(--yp-radius-sm); color: var(--yp-text-primary); font: inherit; font-size: 13px; line-height: 18px; cursor: pointer; }
.calendar-day small { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; line-height: 14px; }
.is-workday { background: var(--yp-bg-surface); }
.is-rest { background: var(--yp-bg-sunken); color: var(--yp-text-muted); }
.is-holiday { background: color-mix(in srgb,var(--yp-status-red) 12%,var(--yp-bg-surface)); color: var(--yp-status-red); }
.is-makeup { background: color-mix(in srgb,var(--yp-status-blue) 12%,var(--yp-bg-surface)); color: var(--yp-status-blue); }
.calendar-day.is-explicit { border-color: var(--yp-border-strong); }
.calendar-day.is-today { outline: 2px solid var(--yp-status-blue); outline-offset: -2px; }
.calendar-day:hover:not(:disabled) { border-color: var(--yp-status-blue); }
.calendar-day:disabled { cursor: default; }
.calendar-editor,.calendar-editor label { display: grid; gap: var(--yp-space-2); }
.calendar-editor { gap: var(--yp-space-4); }
.calendar-editor .el-input-number { width: 100%; }
.calendar-alert { padding: var(--yp-space-4); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.calendar-alert h3 { margin: 0 0 var(--yp-space-3); color: var(--yp-text-primary); }
.calendar-alert p { margin-bottom: 0; color: var(--yp-text-secondary); line-height: 1.6; }
@media (max-width: 1280px) { .calendar-months { grid-template-columns: repeat(3,minmax(0,1fr)); } }
@media (max-width: 760px) { .calendar-months { grid-template-columns: minmax(0,1fr); } .calendar-state { flex-basis: 100%; } .calendar-toolbar { align-items: flex-start; } }
</style>
