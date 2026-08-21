<script setup lang="ts">
import { ProjectLifecycle, readCsrfToken, type ProjectDetail } from '@yumpoo/api-client'
import { ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElMessage, ElMessageBox, ElTag } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'

const route = useRoute()
const project = ref<ProjectDetail>()
const loading = ref(false)
const activating = ref(false)
const error = ref<ApiProblem>()
const projectId = String(route.params.projectId)

async function load(): Promise<void> {
  loading.value = true
  try { project.value = await projectsApi.getProject({ projectId }) }
  catch (reason) { error.value = await toApiProblem(reason) }
  finally { loading.value = false }
}

async function activate(): Promise<void> {
  if (!project.value?.capabilities.canActivate || project.value.lifecycle !== ProjectLifecycle.Draft) return
  try { await ElMessageBox.confirm('激活后 Project 将进入日常交付状态。确认继续？', '激活 Project', { type: 'warning' }) }
  catch { return }
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  activating.value = true
  try {
    await projectsApi.activateProject({ projectId, xXSRFTOKEN: csrf,
      ifMatch: project.value.etag, idempotencyKey: crypto.randomUUID() })
    ElMessage.success('Project 已激活')
    await load()
  } catch (reason) { error.value = await toApiProblem(reason); await load() }
  finally { activating.value = false }
}
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <inline-problem v-if="error" class="inline-error" :problem="error" />
    <div v-if="project" class="page-title compact">
      <div><p class="eyebrow">{{ project.code }}</p><h2>{{ project.name }}</h2><p>{{ project.workspaceName }} · {{ project.ownerDisplayName }}</p></div>
      <div class="header-actions"><el-tag effect="plain">{{ project.lifecycle }}</el-tag><el-button v-if="project.capabilities.canActivate" type="primary" :loading="activating" @click="activate">激活 Project</el-button></div>
    </div>
    <el-card v-if="project" shadow="never">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="项目类型">{{ project.projectType }}</el-descriptions-item>
        <el-descriptions-item label="访问模式">{{ project.actorAccess }}</el-descriptions-item>
        <el-descriptions-item label="固化模板">{{ project.templateKey }} v{{ project.templateVersion }}</el-descriptions-item>
        <el-descriptions-item label="客户名称">{{ project.customerName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="客户参考号">{{ project.customerReference || '—' }}</el-descriptions-item>
        <el-descriptions-item label="交付地点">{{ project.deliverySite || '—' }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ project.description || '—' }}</el-descriptions-item>
        <el-descriptions-item label="联系备注" :span="2">{{ project.contactNote || '—' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>
