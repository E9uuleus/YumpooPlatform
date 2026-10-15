<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDrawer, ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import type { TimeTrackingSession } from '@yumpoo/api-client'
import { timeTrackingApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { formatTrackingDuration, timerMutation, useTimeTracker } from '../../composables/useTimeTracker'
import { addDays, todayIn } from '../team/teamDashboardModel'
import TimeSessionForm from '../timer/TimeSessionForm.vue'
import { companyDateTime, parseCompanyDateTime } from '../timer/timeFormat'
import DayFillPanel from './DayFillPanel.vue'
import MyDayTimeline from './MyDayTimeline.vue'
import QuickFillDialog from './QuickFillDialog.vue'
import { gaps, type DayBlock } from './myDayModel'
import { useMyDay } from './useMyDay'

const props = defineProps<{ date: string; timezone: string }>()
const emit = defineEmits<{ close: []; 'update:date': [date: string] }>()
const tracker = useTimeTracker()
const timeline = ref<InstanceType<typeof MyDayTimeline>>()
const { page, blocks, dayStart, dayEnd, loading, problem, load, fill } = useMyDay(computed(() => props.timezone))
const today = computed(() => todayIn(props.timezone, new Date(tracker.now.value)))
const total = computed(() => Math.max(0, Math.min(dayEnd.value, tracker.now.value) - dayStart.value)
  - gaps(blocks.value, dayStart.value, dayEnd.value, tracker.now.value).reduce((sum, [start, end]) => sum + end - start, 0))
const selection = ref<{ start: number; end: number }>()
const editing = ref<TimeTrackingSession>()
const start = ref(''), end = ref(''), saveProblem = ref('')
const busy = ref(false)
const quick = ref(false)
watch(() => props.date, date => {
  selection.value = undefined; editing.value = undefined; quick.value = false
  void load(date)
}, { immediate: true })
function select(start: number, end: number) { editing.value = undefined; selection.value = { start, end } }
function edit(block: DayBlock) {
  const record = page.value?.items.find(item => item.session.id === block.id)?.session
  if (!record) return
  selection.value = undefined; editing.value = record; saveProblem.value = ''
  start.value = companyDateTime(record.startedAt, props.timezone).slice(0, 16)
  end.value = companyDateTime(record.stoppedAt!, props.timezone).slice(0, 16)
}
async function save() {
  const record = editing.value
  if (!record || busy.value) return
  busy.value = true; saveProblem.value = ''
  try {
    const startedAt = parseCompanyDateTime(start.value, props.timezone), stoppedAt = parseCompanyDateTime(end.value, props.timezone)
    if (stoppedAt <= startedAt || stoppedAt.getTime() > Date.now()) throw new Error('结束时间必须晚于开始时间，且不能在未来')
    const body = { startedAt, stoppedAt }
    await timerMutation(JSON.stringify(['history', record.workItemId, record.etag, body]), (idempotencyKey, xXSRFTOKEN) => timeTrackingApi.editTimeSession({
      workItemId: record.workItemId!, sessionId: record.id, ifMatch: record.etag, idempotencyKey, xXSRFTOKEN, timeTrackingCommand: body,
    }))
    editing.value = undefined
    ElMessage.success('已保存计时记录')
  } catch (error) {
    saveProblem.value = error instanceof Error && !('response' in error) ? error.message : problemMessage(await toApiProblem(error))
  } finally { busy.value = false }
}
async function remove() {
  const record = editing.value
  if (!record || busy.value) return
  busy.value = true; saveProblem.value = ''
  try {
    await timerMutation(JSON.stringify(['delete', record.id, record.etag]), (idempotencyKey, xXSRFTOKEN) => timeTrackingApi.deleteTimeSession({
      workItemId: record.workItemId!, sessionId: record.id, ifMatch: record.etag, idempotencyKey, xXSRFTOKEN, timeTrackingCommand: {},
    }))
    editing.value = undefined
    ElMessage.success('已删除计时记录')
  } catch (error) { saveProblem.value = problemMessage(await toApiProblem(error)) }
  finally { busy.value = false }
}
</script>

<template>
  <el-drawer
    :model-value="true"
    title="我的一天"
    size="min(1100px, 96vw)"
    append-to-body
    class="my-day-drawer"
    :close-on-click-modal="!busy"
    @opened="timeline?.scrollToMorning()"
    @close="!busy && emit('close')"
  >
    <template #header>
      <div class="my-day__header">
        <strong>我的一天</strong>
        <div class="my-day__date">
          <el-button
            text
            :icon="ArrowLeft"
            aria-label="前一天"
            :disabled="busy"
            @click="emit('update:date', addDays(date, -1))"
          />
          <span>{{ date }}</span>
          <el-button
            text
            :icon="ArrowRight"
            aria-label="后一天"
            :disabled="date >= today || busy"
            @click="emit('update:date', addDays(date, 1))"
          />
        </div>
        <span class="my-day__total">当日合计 {{ formatTrackingDuration(total) }}</span>
        <el-button
          :disabled="loading || busy"
          @click="quick = true"
        >
          按时长补录
        </el-button>
      </div>
    </template>
    <el-alert
      v-if="problem"
      :title="problem"
      type="error"
      :closable="false"
    >
      <el-button
        text
        @click="load(date)"
      >
        重试
      </el-button>
    </el-alert>
    <p class="my-day__hint">
      在空白处拖选时段补录，点击记录可编辑。时间按公司时区（{{ timezone }}）显示。
    </p>
    <div
      v-loading="loading && !page"
      class="my-day__body"
      :class="{ 'has-panel': selection || editing }"
    >
      <MyDayTimeline
        ref="timeline"
        :blocks="blocks"
        :day-start="dayStart"
        :day-end="dayEnd"
        :now="tracker.now.value"
        :timezone="timezone"
        @select="select"
        @edit="edit"
      />
      <aside
        v-if="selection || editing"
        class="my-day__side"
      >
        <DayFillPanel
          v-if="selection"
          :key="`${selection.start}-${selection.end}`"
          :start="selection.start"
          :end="selection.end"
          :timezone="timezone"
          :fill="fill"
          @close="selection = undefined"
        />
        <section
          v-else-if="editing"
          class="my-day__edit"
        >
          <header>
            <h3>编辑时长记录</h3><el-button
              text
              :disabled="busy"
              @click="editing = undefined"
            >
              取消
            </el-button>
          </header>
          <TimeSessionForm
            v-model:start="start"
            v-model:end="end"
            :timezone="timezone"
            editing
            :busy="busy"
            :problem="saveProblem"
            @save="save"
          />
          <el-button
            type="danger"
            text
            :disabled="busy"
            @click="remove"
          >
            删除记录
          </el-button>
        </section>
      </aside>
    </div>
    <QuickFillDialog
      v-if="quick"
      :date="date"
      :timezone="timezone"
      :blocks="blocks"
      :day-start="dayStart"
      :day-end="dayEnd"
      :now="tracker.now.value"
      :fill="fill"
      @close="quick = false"
    />
  </el-drawer>
</template>

<style scoped>
.my-day__header{display:flex;flex-wrap:wrap;align-items:center;gap:12px;color:var(--yp-text-primary)}.my-day__date{display:flex;align-items:center;gap:4px;font-variant-numeric:tabular-nums}.my-day__total{font-size:13px;color:var(--yp-text-secondary);margin-left:auto}.my-day__hint{margin:0 0 12px;font-size:12px;color:var(--yp-text-muted)}.my-day__body{display:grid;grid-template-columns:minmax(0,1fr);gap:16px;height:calc(100dvh - 170px);min-height:300px}.my-day__body.has-panel{grid-template-columns:minmax(0,1fr) 360px}.my-day__side{overflow:auto}.my-day__edit{padding:12px;border:1px solid var(--yp-border-subtle);border-radius:var(--yp-radius-md)}.my-day__edit header{display:flex;align-items:center;justify-content:space-between}.my-day__edit h3{font-size:15px;margin:0}.my-day__edit :deep(.manual-session){padding-inline:0}.my-day__edit :deep(.session-times){gap:10px}
@media(max-width:800px){.my-day__body.has-panel{grid-template-columns:minmax(0,1fr)}.my-day__body.has-panel>.my-day-timeline{height:340px}.my-day__body.has-panel{height:auto}.my-day__total{margin-left:0}}
</style>
