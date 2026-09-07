<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElButton, ElDialog, ElMessage, ElMessageBox } from 'element-plus'
import type { TimeTrackingSession, TimeTrackingSessionPage } from '@yumpoo/api-client'
import { timeTrackingApi } from '../../api/client'
import { useSession } from '../../composables/useSession'
import { formatDuration, timerMutation } from '../../composables/useTimeTracker'
import { companyDateTime, parseCompanyDateTime } from './timeFormat'

const props = defineProps<{ workItemId: string; title: string }>()
const emit = defineEmits<{ close: [] }>()
const session = useSession()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'UTC')
const page = ref<TimeTrackingSessionPage>()
const records = ref<TimeTrackingSession[]>([])
const loading = ref(false)
const problem = ref('')
const editing = ref<TimeTrackingSession>()
const form = ref(false)
const start = ref('')
const end = ref('')
const reason = ref('')
async function load(cursor?: string) {
  loading.value = true; problem.value = ''
  try {
    page.value = await timeTrackingApi.listTimeSessions({ workItemId: props.workItemId, ...(cursor ? { cursor } : {}) })
    records.value = cursor ? [...records.value, ...page.value.items] : page.value.items
  } catch { problem.value = '无法加载计时记录，请重试。' } finally { loading.value = false }
}
function edit(record?: TimeTrackingSession) {
  editing.value = record
  start.value = companyDateTime(record?.startedAt ?? new Date(Date.now() - 3600000), timezone.value)
  end.value = companyDateTime(record?.stoppedAt ?? new Date(), timezone.value)
  reason.value = ''; form.value = true
}
async function save() {
  loading.value = true
  try {
    const startedAt = parseCompanyDateTime(start.value, timezone.value)
    const stoppedAt = parseCompanyDateTime(end.value, timezone.value)
    if (stoppedAt <= startedAt || stoppedAt.getTime() > Date.now()) throw new Error('结束时间必须晚于开始时间，且不能在未来')
    if (editing.value && !reason.value.trim()) throw new Error('请填写修正原因')
    const body = { startedAt, stoppedAt, reason: reason.value.trim() }
    const record = editing.value
    await timerMutation(JSON.stringify(['history', props.workItemId, record?.etag, body]), (idempotencyKey, xXSRFTOKEN) => {
      const args = { workItemId: props.workItemId, idempotencyKey, xXSRFTOKEN, timeTrackingCommand: body }
      return record ? timeTrackingApi.editTimeSession({ ...args, sessionId: record.id, ifMatch: record.etag }) : timeTrackingApi.createTimeSession(args)
    })
    form.value = false; await load()
  } catch (error) { ElMessage.error(error instanceof Error && !('response' in error) ? error.message : '保存失败，请检查是否与本人其他记录重叠，或记录已被修改。') }
  finally { loading.value = false }
}
async function remove(record: TimeTrackingSession) {
  try {
    const result = await ElMessageBox.prompt('删除后仍保留审计，请填写删除原因。', '删除计时记录', { inputValidator: value => !!value?.trim() || '请填写原因', confirmButtonText: '删除', cancelButtonText: '取消' })
    await timerMutation(JSON.stringify(['delete', record.id, record.etag, result.value]), (idempotencyKey, xXSRFTOKEN) => timeTrackingApi.deleteTimeSession({ workItemId: props.workItemId, sessionId: record.id, ifMatch: record.etag, idempotencyKey, xXSRFTOKEN, timeTrackingCommand: { reason: result.value } }))
    await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error('删除失败，请刷新后重试。') }
}
onMounted(() => load())
</script>

<template>
  <el-dialog :model-value="true" :title="`${title} · 计时明细`" width="min(900px, 94vw)" append-to-body @close="emit('close')">
    <p>全员累计 {{ formatDuration(page?.summary.totalDurationMs ?? 0) }} · 公司时区 {{ timezone }}</p>
    <p v-if="problem" role="alert">{{ problem }} <el-button @click="load()">重试</el-button></p>
    <div class="sessions"><table><thead><tr><th>成员</th><th>开始</th><th>结束</th><th>时长</th><th>来源</th><th>操作</th></tr></thead>
      <tbody><tr v-for="record in records" :key="record.id"><td>{{ record.displayName }}</td><td>{{ companyDateTime(record.startedAt, timezone).replace('T', ' ') }}</td>
        <td>{{ record.stoppedAt ? companyDateTime(record.stoppedAt, timezone).replace('T', ' ') : '计时中 · 先停止再修正' }}</td>
        <td>{{ formatDuration(record.durationMs) }}</td><td>{{ record.source === 'MANUAL' ? '手动补录' : '计时器' }}</td>
        <td><template v-if="record.canEdit"><el-button text @click="edit(record)">编辑</el-button><el-button text type="danger" @click="remove(record)">删除</el-button></template></td></tr></tbody></table></div>
    <el-button v-if="page?.nextCursor" :loading="loading" @click="load(page.nextCursor)">更多记录</el-button>
    <el-button v-if="page?.canCreate" :disabled="loading" @click="edit()">手动补录</el-button>
    <form v-if="form" class="session-form" @submit.prevent="save">
      <label>开始时间 <input v-model="start" type="datetime-local" step="1" required></label>
      <label>结束时间 <input v-model="end" type="datetime-local" step="1" required></label>
      <label>{{ editing ? '修正原因（必填）' : '备注' }} <input v-model="reason" maxlength="500" :required="!!editing"></label>
      <div><el-button native-type="submit" type="primary" :loading="loading">保存</el-button><el-button @click="form = false">取消</el-button></div>
    </form>
  </el-dialog>
</template>

<style scoped>
.sessions{overflow:auto;margin:16px 0}table{width:100%;border-collapse:collapse;font-size:12px}th,td{padding:10px;text-align:left;border-bottom:1px solid var(--el-border-color-lighter);white-space:nowrap}.session-form{display:grid;gap:14px;padding:20px;background:var(--el-fill-color-light);margin-top:16px}label{display:flex;gap:12px;align-items:center}input{padding:7px;border:1px solid var(--el-border-color);border-radius:5px;background:var(--el-bg-color);color:inherit}
</style>
