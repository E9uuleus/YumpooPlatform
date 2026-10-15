<script setup lang="ts">
import { computed, ref, type DefineComponent } from 'vue'
import { ElButton, ElDialog, ElInputNumber, ElMessage, ElOption as ElOptionRaw, ElSelect as ElSelectRaw } from 'element-plus'
import { TimeSessionFillMode, type TimeSessionFillEntry, type TimeSessionFillResult, type TimerCandidate } from '@yumpoo/api-client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { useTimerCandidates } from '../../composables/useTimerCandidates'
import { formatTrackingDuration } from '../../composables/useTimeTracker'
import { parseCompanyDateTime } from '../timer/timeFormat'
import MyDayTimeline from './MyDayTimeline.vue'
import { gaps, placeSequential, type DayBlock } from './myDayModel'

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const props = defineProps<{
  date: string; timezone: string; blocks: DayBlock[]; dayStart: number; dayEnd: number; now: number
  fill: (mode: TimeSessionFillMode, entries: TimeSessionFillEntry[]) => Promise<TimeSessionFillResult>
}>()
const emit = defineEmits<{ close: [] }>()
const timeline = ref<InstanceType<typeof MyDayTimeline>>()
const rows = ref([{ id: 0, workItemId: '', hours: 1, minutes: 0 }])
let nextId = 1
const start = ref('09:00')
const busy = ref(false)
const problem = ref('')
const selectedItems = ref<TimerCandidate[]>([])
const candidates = useTimerCandidates(ref(true))
const options = computed(() => [...new Map([...selectedItems.value, ...candidates.items.value].map(item => [item.workItemId, item])).values()])
function search(value: string) { candidates.query.value = value }
function showCandidates(open: boolean) { if (open) candidates.query.value = '' }
function select(id: string) {
  const item = options.value.find(item => item.workItemId === id)
  if (item && !selectedItems.value.some(selected => selected.workItemId === id)) selectedItems.value.push(item)
}
const schedule = computed(() => {
  try {
    const startAt = parseCompanyDateTime(`${props.date}T${start.value}`, props.timezone).getTime()
    const result = placeSequential(rows.value.filter(row => row.workItemId).map(row => ({ workItemId: row.workItemId, durationMs: ((row.hours || 0) * 60 + (row.minutes || 0)) * 60_000 })), gaps(props.blocks, props.dayStart, props.dayEnd, props.now), startAt)
    return { ...result, problem: result.entries.length > 20 ? '本次安排超过 20 个时间段，请减少补录条目或时长后保存。' : '' }
  } catch (error) { return { entries: [], leftoverMs: 0, problem: (error as Error).message } }
})
const preview = computed<DayBlock[]>(() => schedule.value.entries.map((entry, index) => {
  const item = options.value.find(item => item.workItemId === entry.workItemId)
  return { ...entry, id: `preview-${index}`, title: item?.title ?? '', itemNo: item?.itemNo ?? null, projectName: item?.projectName ?? null,
    colorToken: item?.contentColorToken ?? null, canEdit: false, allocatedMs: entry.end - entry.start, parallelItemCount: 0 }
}))
async function save() {
  busy.value = true
  problem.value = ''
  try {
    const result = await props.fill(TimeSessionFillMode.Gaps, schedule.value.entries.map(entry => ({ workItemId: entry.workItemId, startedAt: new Date(entry.start), stoppedAt: new Date(entry.end) })))
    if (result.skippedDurationMs > 0) ElMessage.info(`已跳过 ${formatTrackingDuration(result.skippedDurationMs)}（该时段已有记录）`)
    else ElMessage.success('已补录工时')
    emit('close')
  } catch (error) { problem.value = problemMessage(await toApiProblem(error)) }
  finally { busy.value = false }
}
</script>

<template>
  <el-dialog
    :model-value="true"
    title="按时长补录"
    width="min(1040px, 96vw)"
    :close-on-click-modal="!busy"
    @opened="timeline?.scrollToMorning()"
    @close="!busy && emit('close')"
  >
    <div class="quick-fill">
      <div class="quick-fill__form">
        <label class="quick-fill__start">从 <input
          v-model="start"
          type="time"
          step="300"
          :disabled="busy"
          required
        > 开始</label>
        <p class="quick-fill__hint">
          按行依次排入当天空白时段，右侧虚线为保存预览。
        </p>
        <div
          v-for="(row, index) in rows"
          :key="row.id"
          class="quick-fill__row"
        >
          <el-select
            v-model="row.workItemId"
            filterable
            remote
            :remote-method="search"
            :loading="candidates.loading.value"
            :disabled="busy"
            :aria-label="`第 ${index + 1} 行工作项`"
            placeholder="搜索并选择工作项"
            @change="select"
            @visible-change="showCandidates"
          >
            <el-option
              v-for="item in options"
              :key="item.workItemId"
              :value="item.workItemId"
              :label="`#${item.itemNo} ${item.title} · ${item.projectName}`"
            />
          </el-select>
          <div class="quick-fill__duration">
            <el-input-number
              v-model="row.hours"
              :min="0"
              :max="24"
              :precision="0"
              :controls="false"
              :disabled="busy"
              :aria-label="`第 ${index + 1} 行小时`"
            /><span>小时</span>
            <el-input-number
              v-model="row.minutes"
              :min="0"
              :max="59"
              :precision="0"
              :controls="false"
              :disabled="busy"
              :aria-label="`第 ${index + 1} 行分钟`"
            /><span>分钟</span>
            <el-button
              text
              :disabled="busy || rows.length === 1"
              :aria-label="`删除第 ${index + 1} 行`"
              @click="rows.splice(index, 1)"
            >
              删除
            </el-button>
          </div>
        </div>
        <el-button
          :disabled="busy"
          @click="rows.push({ id: nextId++, workItemId: '', hours: 1, minutes: 0 })"
        >
          添加一行
        </el-button>
        <p
          v-if="candidates.problem.value"
          class="quick-fill__error"
          role="alert"
        >
          {{ candidates.problem.value }}
        </p>
        <p
          v-if="schedule.leftoverMs"
          class="quick-fill__hint"
        >
          当天空白不足，剩余 {{ formatTrackingDuration(schedule.leftoverMs) }} 未排入
        </p>
        <p
          v-if="problem || schedule.problem"
          class="quick-fill__error"
          role="alert"
        >
          {{ problem || schedule.problem }}
        </p>
      </div>
      <MyDayTimeline
        ref="timeline"
        :blocks="blocks"
        :preview="preview"
        :day-start="dayStart"
        :day-end="dayEnd"
        :now="now"
        :timezone="timezone"
      />
    </div>
    <template #footer>
      <el-button
        :disabled="busy"
        @click="emit('close')"
      >
        取消
      </el-button>
      <el-button
        type="primary"
        :disabled="!schedule.entries.length || !!schedule.problem"
        :loading="busy"
        @click="save"
      >
        保存补录
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.quick-fill{display:grid;grid-template-columns:340px minmax(0,1fr);gap:20px;height:min(620px,65dvh)}.quick-fill__form{overflow:auto;min-width:0}.quick-fill__start{display:flex;align-items:center;gap:8px;color:var(--yp-text-primary)}.quick-fill__start input{border:1px solid var(--yp-input-border);border-radius:var(--yp-input-radius);padding:6px;background:var(--yp-bg-surface);color:inherit;font:inherit}.quick-fill__hint{font-size:12px;line-height:1.6;color:var(--yp-text-secondary)}.quick-fill__row{display:grid;gap:8px;padding:12px 0;border-bottom:1px solid var(--yp-border-subtle);margin-bottom:12px}.quick-fill__duration{display:flex;align-items:center;gap:6px;font-size:12px}.quick-fill__duration .el-input-number{width:64px}.quick-fill__error{color:var(--el-color-danger);font-size:13px}
@media(max-width:760px){.quick-fill{grid-template-columns:1fr;height:auto}.quick-fill__form{max-height:35dvh}.quick-fill>.my-day-timeline{height:300px}}
</style>
