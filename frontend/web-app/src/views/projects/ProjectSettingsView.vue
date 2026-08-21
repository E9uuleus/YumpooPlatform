<script setup lang="ts">
import { readCsrfToken, type ProjectDetail, type ProjectUpdateRequest } from '@yumpoo/api-client'
import { ElButton, ElForm, ElFormItem, ElInput, ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'

const route = useRoute()
const projectId = String(route.params.projectId)
const project = ref<ProjectDetail>()
const error = ref<ApiProblem>()
const saving = ref(false)
const form = reactive({ name: '', description: '', customerName: '', customerReference: '', deliverySite: '', contactNote: '' })

function fill(next: ProjectDetail): void {
  form.name = next.name; form.description = next.description ?? ''; form.customerName = next.customerName ?? ''
  form.customerReference = next.customerReference ?? ''; form.deliverySite = next.deliverySite ?? ''
  form.contactNote = next.contactNote ?? ''
}
async function load(replaceDraft = true): Promise<void> {
  try { const next = await projectsApi.getProject({ projectId }); project.value = next; if (replaceDraft) fill(next) }
  catch (reason) { error.value = await toApiProblem(reason) }
}
async function save(): Promise<void> {
  if (!project.value?.capabilities.canUpdateSettings) return
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  const snapshot: ProjectUpdateRequest = { name: form.name.trim(), description: form.description.trim() || null,
    customerName: form.customerName.trim() || null, customerReference: form.customerReference.trim() || null,
    deliverySite: form.deliverySite.trim() || null, contactNote: form.contactNote.trim() || null }
  saving.value = true
  try {
    project.value = await projectsApi.updateProject({ projectId, xXSRFTOKEN: csrf,
      ifMatch: project.value.etag, projectUpdateRequest: snapshot })
    fill(project.value); ElMessage.success('项目设置已保存')
  } catch (reason) {
    const problem = await toApiProblem(reason); error.value = problem
    if (isProblemStatus(problem, 412)) await load(false)
  } finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <div>
    <div class="page-title compact"><div><p class="eyebrow">PROJECT SETTINGS</p><h2>项目设置</h2><p>保存时发送完整可变字段快照。</p></div></div>
    <inline-problem v-if="error" class="inline-error" :problem="error" />
    <el-form label-position="top" class="settings-form" :model="form">
      <el-form-item label="项目名称"><el-input v-model="form.name" maxlength="80" /></el-form-item>
      <el-form-item label="描述"><el-input v-model="form.description" type="textarea" maxlength="500" show-word-limit /></el-form-item>
      <div class="form-grid"><el-form-item label="客户名称"><el-input v-model="form.customerName" maxlength="160" /></el-form-item><el-form-item label="客户参考号"><el-input v-model="form.customerReference" maxlength="80" /></el-form-item></div>
      <el-form-item label="交付地点"><el-input v-model="form.deliverySite" maxlength="160" /></el-form-item>
      <el-form-item label="联系备注"><el-input v-model="form.contactNote" type="textarea" maxlength="500" show-word-limit /></el-form-item>
      <p v-if="project && !project.capabilities.canUpdateSettings" class="actor-label">当前访问模式不可修改项目设置。</p>
      <el-button type="primary" :disabled="!project?.capabilities.canUpdateSettings" :loading="saving" @click="save">保存设置</el-button>
    </el-form>
  </div>
</template>
