<script setup lang="ts">
import { readCsrfToken, type ProjectSummary } from '@yumpoo/api-client'
import { ElButton, ElMessage, ElMessageBox } from 'element-plus'
import { ref } from 'vue'
import { projectsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { notifyProjectLifecycleChanged } from '../../composables/projectLifecycleEvents'

const props = defineProps<{ project: ProjectSummary }>()
const emit = defineEmits<{ changed: []; problem: [problem: ApiProblem] }>()
const busy = ref(false)

async function schedule(): Promise<void> {
  if (busy.value) return
  const target = props.project
  busy.value = true
  let confirmationCode: string
  try {
    const answer = await ElMessageBox.prompt(
      `永久删除将清除“${target.name}”的工作项、讨论、附件、工时和连接。默认有30天缓冲期，确切删除时间在提交后显示。请输入项目编号 ${target.code}。`,
      '计划永久删除项目', {
        type: 'warning', confirmButtonText: '计划删除', cancelButtonText: '取消',
        inputPlaceholder: target.code,
        inputValidator: value => value === target.code || '请输入准确的项目编号',
      },
    )
    confirmationCode = answer.value
  } catch { busy.value = false; return }
  await mutate(target, true, confirmationCode)
}
async function cancel(): Promise<void> {
  if (busy.value) return
  const target = props.project
  busy.value = true
  try {
    await ElMessageBox.confirm(`撤销“${target.name}”的删除计划后，项目仍保持归档，可再恢复。`, '撤销删除', {
      type: 'warning', confirmButtonText: '撤销删除', cancelButtonText: '返回',
    })
  } catch { busy.value = false; return }
  await mutate(target, false)
}
async function mutate(target: ProjectSummary, scheduleDeletion: boolean, confirmationCode?: string): Promise<void> {
  const token = readCsrfToken()
  if (!token) { busy.value = false; emit('problem', localProblem('缺少 CSRF 凭据，请刷新后重试。')); return }
  try {
    const headers = { projectId: target.id, ifMatch: target.etag, xXSRFTOKEN: token, idempotencyKey: crypto.randomUUID() }
    if (scheduleDeletion) await projectsApi.scheduleProjectDeletion({
      ...headers, projectDeletionRequest: { confirmationCode: confirmationCode ?? '' },
    })
    else await projectsApi.cancelProjectDeletion(headers)
    ElMessage.success(scheduleDeletion ? '项目已计划删除，可在缓冲期内撤销。' : '删除计划已撤销')
    notifyProjectLifecycleChanged(target.id)
    emit('changed')
  } catch (reason) {
    const problem = await toApiProblem(reason)
    emit('changed')
    if (isProblemStatus(problem, 412)) ElMessage.warning('项目状态已更新，请根据最新状态重试。')
    emit('problem', problem)
  } finally { busy.value = false }
}
</script>

<template>
  <el-button
    v-if="project.capabilities.canScheduleDeletion"
    link
    type="danger"
    :loading="busy"
    @click="schedule"
  >
    删除
  </el-button>
  <el-button
    v-if="project.capabilities.canCancelDeletion"
    link
    :loading="busy"
    @click="cancel"
  >
    撤销删除
  </el-button>
</template>
