<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElButton, ElMessage, ElTooltip } from 'element-plus'
import type { ErrorResponse, TimeTrackingSession, TimeTrackingSessionPage } from '@yumpoo/api-client'
import { timeTrackingApi } from '../../api/client'
import { useSession } from '../../composables/useSession'
import { formatDuration, timerMutation } from '../../composables/useTimeTracker'
import TimeSessionForm from './TimeSessionForm.vue'
import { companyDateTime, parseCompanyDateTime } from './timeFormat'

const props = defineProps<{ workItemId: string; title: string }>()
const emit = defineEmits<{ close: []; resize: [height: number] }>()
const session = useSession()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'UTC')
const page = ref<TimeTrackingSessionPage>()
const records = ref<TimeTrackingSession[]>([])
const loading = ref(false)
const deleting = ref<TimeTrackingSession | 'all'>()
const deleteReason = ref('')
const mutating = ref(false)
const problem = ref('')
const editing = ref<TimeTrackingSession>()
const form = ref(false)
const fullCalendar = ref(false)
watch([form, fullCalendar], ([adding, full]) => emit('resize', adding ? full ? 580 : 460 : 400))
const start = ref('')
const end = ref('')
const saveProblem = ref('')
async function load(cursor?: string) {
  if (loading.value) return
  loading.value = true; problem.value = ''
  try {
    page.value = await timeTrackingApi.listTimeSessions({ workItemId: props.workItemId, ...(cursor ? { cursor } : {}) })
    records.value = cursor ? [...records.value, ...page.value.items] : page.value.items
  } catch { problem.value = '无法加载计时记录，请重试。' } finally { loading.value = false }
}
function edit(record?: TimeTrackingSession) {
  editing.value = record
  start.value = companyDateTime(record?.startedAt ?? new Date(Date.now() - 3600000), timezone.value).slice(0, 16)
  end.value = companyDateTime(record?.stoppedAt ?? new Date(), timezone.value).slice(0, 16)
  saveProblem.value = ''; fullCalendar.value = false; form.value = true
}
async function save() {
  if (loading.value) return
  saveProblem.value = ''
  loading.value = true
  try {
    const startedAt = parseCompanyDateTime(start.value, timezone.value)
    const stoppedAt = parseCompanyDateTime(end.value, timezone.value)
    if (stoppedAt <= startedAt || stoppedAt.getTime() > Date.now()) throw new Error('结束时间必须晚于开始时间，且不能在未来')
    const body = { startedAt, stoppedAt }
    const record = editing.value
    await timerMutation(JSON.stringify(['history', props.workItemId, record?.etag, body]), (idempotencyKey, xXSRFTOKEN) => {
      const args = { workItemId: props.workItemId, idempotencyKey, xXSRFTOKEN, timeTrackingCommand: body }
      return record ? timeTrackingApi.editTimeSession({ ...args, sessionId: record.id, ifMatch: record.etag }) : timeTrackingApi.createTimeSession(args)
    })
    form.value = false; loading.value = false; await load()
  } catch (error) {
    saveProblem.value = error instanceof Error && !('response' in error) ? error.message : '保存失败，请重试。'
    const response = (error as { response?: Response }).response
    if (response) {
      try {
        const detail = await response.clone().json() as ErrorResponse
        saveProblem.value = detail.fieldErrors?.map(field => field.message).join('；') || detail.message || saveProblem.value
      } catch { /* Preserve the fallback when the response is not JSON. */ }
    }
  }
  finally { loading.value = false }
}
function requestRemove(record: TimeTrackingSession | 'all') {
  deleting.value = record; deleteReason.value = ''; form.value = false
}
async function remove() {
  if (!deleting.value || !deleteReason.value.trim() || mutating.value) return
  mutating.value = true
  let removed = 0
  try {
    let targets: TimeTrackingSession[]
    if (deleting.value === 'all') {
      targets = []
      let cursor: string | undefined
      do {
        const result = await timeTrackingApi.listTimeSessions({ workItemId: props.workItemId, ...(cursor ? { cursor } : {}) })
        targets.push(...result.items.filter(record => record.canEdit && record.stoppedAt))
        cursor = result.nextCursor ?? undefined
      } while (cursor)
    } else targets = [deleting.value]
    for (const record of targets) {
      await timerMutation(JSON.stringify(['delete', record.id, record.etag, deleteReason.value]), (idempotencyKey, xXSRFTOKEN) => timeTrackingApi.deleteTimeSession({ workItemId: props.workItemId, sessionId: record.id, ifMatch: record.etag, idempotencyKey, xXSRFTOKEN, timeTrackingCommand: { reason: deleteReason.value.trim() } }))
      removed++
    }
    deleting.value = undefined
    ElMessage.success(removed ? `已清除 ${removed} 条计时记录` : '没有可清除的已停止记录')
  } catch { ElMessage.error(`已清除 ${removed} 条记录，其余未完成，请刷新后重试。`) }
  finally { mutating.value = false; await load() }
}
function loadMore() {
  if (page.value?.nextCursor && !loading.value && !mutating.value) void load(page.value.nextCursor)
}
function onScroll(event: Event) {
  const list = event.currentTarget as HTMLElement
  if (!problem.value && list.scrollHeight - list.scrollTop - list.clientHeight < 80) loadMore()
}
function dateLabel(value: Date): string {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: timezone.value, month: 'numeric', day: 'numeric' }).format(value)
}
function clockLabel(value: Date): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone: timezone.value, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(value)
}
onMounted(() => load())
</script>

<template>
  <section
    class="time-log"
    :class="{ 'time-log--adding': form, 'time-log--full-calendar': form && fullCalendar }"
    role="dialog"
    :aria-label="form ? editing ? '编辑时长记录' : '添加时长记录' : '计时日志'"
    :aria-busy="loading || mutating"
    @keydown.esc.stop="emit('close')"
    @click.stop
    @pointerdown.stop
  >
    <header>
      <h3>{{ form ? editing ? '编辑时长记录' : '添加时长记录' : '计时日志' }}</h3>
      <button
        v-if="form"
        class="plain-button"
        :disabled="loading"
        @click="form = false"
      >
        返回
      </button><button
        v-else
        class="plain-button"
        :disabled="loading || mutating || !records.length"
        @click="requestRemove('all')"
      >
        清除
      </button>
    </header>
    <div
      v-if="!form"
      class="add-session"
    >
      <button
        v-if="page?.canCreate"
        class="plain-button"
        :disabled="loading || mutating"
        @click="deleting = undefined; edit()"
      >
        <span aria-hidden="true">＋</span> 手动添加时长记录
      </button>
    </div>
    <div
      class="log-body"
      @scroll="onScroll"
    >
      <form
        v-if="deleting"
        class="session-form"
        @submit.prevent="remove"
      >
        <p>{{ deleting === 'all' ? '清除本人所有可编辑的已停止记录（包含尚未加载的记录）。' : '删除这条计时记录。' }}删除后保留审计。</p>
        <label>删除原因<input
          v-model="deleteReason"
          maxlength="500"
          required
          :disabled="mutating"
        ></label>
        <div>
          <el-button
            native-type="submit"
            type="primary"
            :loading="mutating"
          >
            确认{{ deleting === 'all' ? '清除' : '删除' }}
          </el-button><button
            type="button"
            class="plain-button"
            :disabled="mutating"
            @click="deleting = undefined"
          >
            取消
          </button>
        </div>
      </form>
      <TimeSessionForm
        v-else-if="form"
        v-model:start="start"
        v-model:end="end"
        :timezone="timezone"
        :editing="!!editing"
        :busy="loading"
        :problem="saveProblem"
        @expand="fullCalendar = $event"
        @save="save"
      />
      <template v-else>
        <div
          v-for="record in records"
          :key="record.id"
          class="log-row"
        >
          <el-tooltip
            :content="record.displayName"
            placement="top"
          >
            <span
              class="member-badge"
              :aria-label="record.displayName"
            >{{ Array.from(record.displayName)[0] }}</span>
          </el-tooltip>
          <button
            class="plain-button record-date"
            :class="{ 'is-edited': record.rowVersion > (record.source === 'TIMER' ? 1 : 0) }"
            :disabled="!record.canEdit"
            aria-label="编辑记录日期"
            @click="edit(record)"
          >
            {{ dateLabel(record.startedAt) }}
          </button>
          <button
            class="plain-button time-range"
            :class="{ 'is-edited': record.rowVersion > (record.source === 'TIMER' ? 1 : 0) }"
            :disabled="!record.canEdit"
            aria-label="编辑开始结束时间"
            @click="edit(record)"
          >
            {{ clockLabel(record.startedAt) }} - {{ record.stoppedAt ? clockLabel(record.stoppedAt) : '计时中' }}
          </button>
          <button
            class="plain-button record-duration"
            :disabled="!record.canEdit"
            @click="edit(record)"
          >
            {{ formatDuration(record.durationMs).padStart(8, '0') }}
          </button>
          <button
            v-if="record.canEdit"
            class="plain-button delete-button"
            :aria-label="`删除 ${record.displayName} 的计时记录`"
            @click="requestRemove(record)"
          >
            <svg
              viewBox="0 0 24 24"
              aria-hidden="true"
            ><path d="M4 6h16M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7" /></svg>
          </button>
          <span v-else />
        </div>
        <p
          v-if="!loading && !problem && !records.length"
          class="log-status"
        >
          暂无计时记录
        </p>
        <p
          v-if="loading"
          class="log-status"
          role="status"
        >
          正在加载…
        </p>
        <p
          v-if="problem"
          class="log-status"
          role="alert"
        >
          {{ problem }} <button
            class="plain-button"
            @click="load(page?.nextCursor ?? undefined)"
          >
            重试
          </button>
        </p>
        <button
          v-else-if="page?.nextCursor"
          class="plain-button load-more"
          :disabled="loading"
          @click="loadMore"
        >
          加载更多记录
        </button>
      </template>
    </div>
  </section>
</template>

<style scoped>
.time-log{height:400px;max-height:calc(100dvh - 120px);display:flex;flex-direction:column;color:var(--el-text-color-primary);font-size:14px;white-space:normal}
header{display:flex;align-items:center;justify-content:space-between;padding:18px 20px 8px;flex-shrink:0}h3{margin:0;font-size:20px;font-weight:600}
.plain-button{border:0;background:transparent;color:inherit;font:inherit;cursor:pointer;border-radius:5px;padding:7px 9px}.plain-button:hover:not(:disabled){background:#f0f1f3}.plain-button:disabled{cursor:default;opacity:.5}.plain-button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
.add-session{display:flex;justify-content:center;min-height:52px;align-items:center;flex-shrink:0}.add-session span{font-size:21px;margin-right:7px}
.log-body{overflow-y:auto;overscroll-behavior:contain;flex:1;min-height:0;padding:0 12px 10px;scrollbar-gutter:stable}
.log-row{display:grid;grid-template-columns:26px 48px minmax(105px,1fr) 76px 26px;gap:6px;align-items:center;min-height:50px;font-variant-numeric:tabular-nums;white-space:nowrap}
.member-badge{display:grid;place-items:center;width:24px;height:24px;border-radius:6px;background:#09b9b2;color:white;font-size:14px;font-weight:600}.time-range{text-align:center}.record-duration{padding:6px 0;font-variant-numeric:tabular-nums}.record-duration:disabled{opacity:1}
.delete-button{padding:5px;display:grid;place-items:center}.delete-button svg{width:19px;height:19px;fill:none;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}
.log-status{text-align:center;color:var(--el-text-color-secondary);padding:16px 0}.load-more{display:block;margin:8px auto}
.session-form{display:grid;gap:14px;padding:14px 2px}.session-form p{margin:0;line-height:1.6}label{display:grid;gap:6px}input{min-width:0;padding:7px;border:1px solid var(--el-border-color);border-radius:5px;background:var(--el-bg-color);color:inherit;font:inherit}
.time-log--adding{height:460px}.time-log--full-calendar{height:580px}
.time-log--adding .log-body{padding:0 10px;scrollbar-gutter:auto}
@media(max-width:400px){.log-row{grid-template-columns:24px 40px minmax(95px,1fr) 65px 24px;gap:3px;font-size:12px}.log-body{padding-inline:8px}}
.record-date{margin-left:6px;padding:6px 0}.time-range{padding:6px 0}.record-date:disabled,.time-range:disabled{opacity:1}.plain-button.is-edited{color:#D83A52}.time-log :deep(.el-button:hover){background:#f0f1f3;border-color:#e5e7eb;color:var(--el-text-color-primary)}
</style>
