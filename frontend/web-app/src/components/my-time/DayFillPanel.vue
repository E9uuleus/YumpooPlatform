<script setup lang="ts">
import { ref } from 'vue'
import { ElButton, ElMessage, ElTag } from 'element-plus'
import { TimeSessionFillMode, type TimeSessionFillEntry, type TimeSessionFillResult, type TimerCandidate } from '@yumpoo/api-client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { useTimerCandidates } from '../../composables/useTimerCandidates'
import { formatTrackingDuration } from '../../composables/useTimeTracker'
import { companyDateTime, parseCompanyDateTime } from '../timer/timeFormat'

const props = defineProps<{
  start: number; end: number; timezone: string
  fill: (mode: TimeSessionFillMode, entries: TimeSessionFillEntry[]) => Promise<TimeSessionFillResult>
}>()
const emit = defineEmits<{ close: [] }>()
const start = ref(companyDateTime(new Date(props.start), props.timezone).slice(0, 16))
const end = ref(companyDateTime(new Date(props.end), props.timezone).slice(0, 16))
const mode = ref<TimeSessionFillMode>(TimeSessionFillMode.Parallel)
const chosen = ref<TimerCandidate[]>([])
const busy = ref(false)
const saveProblem = ref('')
const { query, items, loading, problem, nextOffset, load } = useTimerCandidates(ref(true))
function select(item: TimerCandidate) {
  const index = chosen.value.findIndex(selected => selected.workItemId === item.workItemId)
  if (index >= 0) chosen.value.splice(index, 1)
  else if (chosen.value.length < 10) chosen.value.push(item)
}
async function save() {
  busy.value = true
  saveProblem.value = ''
  try {
    const startedAt = parseCompanyDateTime(start.value, props.timezone)
    const stoppedAt = parseCompanyDateTime(end.value, props.timezone)
    if (stoppedAt <= startedAt || stoppedAt.getTime() > Date.now()) throw new Error('结束时间必须晚于开始时间，且不能在未来')
    const result = await props.fill(mode.value, chosen.value.map(item => ({ workItemId: item.workItemId, startedAt, stoppedAt })))
    if (result.skippedDurationMs > 0) ElMessage.info(`已跳过 ${formatTrackingDuration(result.skippedDurationMs)}（该时段已有记录）`)
    else ElMessage.success('已补录工时')
    emit('close')
  } catch (error) {
    saveProblem.value = error instanceof Error && !('response' in error) ? error.message : problemMessage(await toApiProblem(error))
  } finally { busy.value = false }
}
</script>

<template>
  <form
    class="day-fill"
    aria-label="补录所选时段"
    @submit.prevent="save"
  >
    <header>
      <h3>补录时段</h3><el-button
        text
        :disabled="busy"
        @click="emit('close')"
      >
        取消
      </el-button>
    </header>
    <fieldset :disabled="busy">
      <div class="day-fill__times">
        <label>开始时间<input
          v-model="start"
          type="datetime-local"
          required
        ></label>
        <label>结束时间<input
          v-model="end"
          type="datetime-local"
          required
        ></label>
      </div>
      <label class="day-fill__search">工作项（最多 10 项）<input
        v-model="query"
        type="search"
        placeholder="搜索工作项或项目"
        maxlength="200"
      ></label>
      <div
        v-if="chosen.length"
        class="day-fill__chosen"
      >
        <el-tag
          v-for="item in chosen"
          :key="item.workItemId"
          closable
          @close="select(item)"
        >
          {{ item.title }}
        </el-tag>
      </div>
      <div
        class="day-fill__candidates"
        role="group"
        aria-label="选择补录工作项"
      >
        <label
          v-for="item in items"
          :key="item.workItemId"
          class="day-fill__candidate"
        >
          <input
            type="checkbox"
            :checked="chosen.some(selected => selected.workItemId === item.workItemId)"
            :disabled="chosen.length >= 10 && !chosen.some(selected => selected.workItemId === item.workItemId)"
            @change="select(item)"
          >
          <span><strong>{{ item.title }}</strong><small>#{{ item.itemNo }} · {{ item.projectName }}</small></span>
        </label>
        <p v-if="loading">
          正在加载…
        </p>
        <p
          v-else-if="problem"
          role="alert"
        >
          {{ problem }} <el-button
            text
            @click="load()"
          >
            重试
          </el-button>
        </p>
        <p v-else-if="!items.length">
          暂无匹配的工作项
        </p>
        <el-button
          v-if="nextOffset !== null"
          text
          :loading="loading"
          @click="load(true)"
        >
          加载更多
        </el-button>
      </div>
      <div
        class="day-fill__modes"
        role="radiogroup"
        aria-label="补录方式"
      >
        <label><input
          v-model="mode"
          type="radio"
          :value="TimeSessionFillMode.Parallel"
        ><span>并行记录<small>与该时段已有记录并行，按项数均摊</small></span></label>
        <label><input
          v-model="mode"
          type="radio"
          :value="TimeSessionFillMode.Replace"
        ><span>替换该时段其他记录<small>裁剪本人可编辑的其他记录</small></span></label>
        <label><input
          v-model="mode"
          type="radio"
          :value="TimeSessionFillMode.Gaps"
        ><span>仅补空白<small>只填没有任何记录的部分</small></span></label>
      </div>
    </fieldset>
    <p
      v-if="saveProblem"
      class="day-fill__error"
      role="alert"
    >
      {{ saveProblem }}
    </p>
    <el-button
      type="primary"
      native-type="submit"
      :disabled="!chosen.length"
      :loading="busy"
    >
      保存补录
    </el-button>
  </form>
</template>

<style scoped>
.day-fill{display:grid;gap:12px;padding:16px;border:1px solid var(--yp-border-subtle);border-radius:var(--yp-radius-md);background:var(--yp-bg-surface)}header{display:flex;align-items:center;justify-content:space-between}h3{margin:0;font-size:15px}fieldset{display:grid;gap:12px;margin:0;padding:0;border:0;min-width:0}.day-fill__times{display:grid;grid-template-columns:1fr 1fr;gap:10px}.day-fill__times label,.day-fill__search{display:grid;gap:6px;font-size:12px;color:var(--yp-text-secondary)}input{font:inherit;color:var(--yp-text-primary);accent-color:var(--yp-action-primary)}input[type=datetime-local],input[type=search]{box-sizing:border-box;width:100%;min-width:0;padding:8px;border:1px solid var(--yp-input-border);border-radius:var(--yp-input-radius);background:var(--yp-bg-surface)}.day-fill__chosen{display:flex;flex-wrap:wrap;gap:6px}.day-fill__candidates{max-height:180px;overflow:auto}.day-fill__candidate{display:flex;align-items:center;gap:8px;padding:7px 0;cursor:pointer}.day-fill__candidate span{display:grid;gap:3px;min-width:0}.day-fill__candidate strong{font-size:13px;font-weight:500}.day-fill__candidate small,.day-fill__modes small{color:var(--yp-text-muted);font-size:11px}.day-fill__modes{display:grid;gap:10px;font-size:13px}.day-fill__modes label{display:flex;align-items:flex-start;gap:7px;cursor:pointer}.day-fill__modes span{display:grid;gap:4px}.day-fill__error{margin:0;color:var(--el-color-danger);font-size:13px}
@media(max-width:480px){.day-fill__times{grid-template-columns:1fr}}
</style>
