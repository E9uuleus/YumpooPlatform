<script setup lang="ts">
import { ProjectLifecycle, readCsrfToken, type ProjectDetail } from '@yumpoo/api-client'
import {
  ElButton,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import { businessLabel } from '../../design-system/labels'
import ProjectWorkspaceHeader from './ProjectWorkspaceHeader.vue'
import ProjectLifecycleActions from './ProjectLifecycleActions.vue'

const route = useRoute()
const project = ref<ProjectDetail>()
const loading = ref(false)
const activating = ref(false)
const error = ref<ApiProblem>()
const projectId = String(route.params.projectId)

function formatTime(value?: Date | null): string {
  return value ? value.toLocaleString('zh-CN') : '—'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    project.value = await projectsApi.getProject({ projectId })
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

function showProblem(problem: ApiProblem): void {
  error.value = problem
}

async function activate(): Promise<void> {
  if (!project.value?.capabilities.canActivate || project.value.lifecycle !== ProjectLifecycle.Draft) return
  try {
    await ElMessageBox.confirm('激活后 Project 将进入日常交付状态。确认继续？', '激活 Project', {
      type: 'warning',
      confirmButtonText: '激活 Project',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  activating.value = true
  try {
    await projectsApi.activateProject({
      projectId,
      xXSRFTOKEN: csrf,
      ifMatch: project.value.etag,
      idempotencyKey: crypto.randomUUID(),
    })
    ElMessage.success('Project 已激活')
    await load()
  } catch (reason) {
    error.value = await toApiProblem(reason)
    await load()
  } finally {
    activating.value = false
  }
}

onMounted(load)
</script>

<template>
  <div
    v-loading="loading"
    class="project-view-stack"
  >
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <template v-if="project">
      <project-workspace-header
        section="overview"
        :project="project"
      >
        <template #primary-action>
          <el-button
            v-if="project.capabilities.canActivate"
            type="primary"
            :loading="activating"
            @click="activate"
          >
            激活 Project
          </el-button>
        </template>
      </project-workspace-header>

      <section class="project-overview-surface">
        <project-lifecycle-actions
          :project="project"
          @changed="load"
          @problem="showProblem"
        />
        <div class="project-section-heading">
          <div>
            <h2>概览</h2>
            <p>项目基本信息与交付上下文。</p>
          </div>
        </div>
        <div class="project-definition-grid">
          <section class="project-definition-section">
            <h2>项目信息</h2>
            <dl>
              <dt>项目类型</dt>
              <dd>{{ businessLabel(project.projectType) }}</dd>
              <dt>访问模式</dt>
              <dd>{{ businessLabel(project.actorAccess) }}</dd>
              <dt>固化模板</dt>
              <dd>{{ project.templateKey }} v{{ project.templateVersion }}</dd>
              <dt>创建时间</dt>
              <dd>{{ formatTime(project.createdAt) }}</dd>
              <dt>更新时间</dt>
              <dd>{{ formatTime(project.updatedAt) }}</dd>
            </dl>
          </section>
          <section class="project-definition-section">
            <h2>交付上下文</h2>
            <dl>
              <dt>客户名称</dt>
              <dd>{{ project.customerName || '—' }}</dd>
              <dt>客户参考号</dt>
              <dd>{{ project.customerReference || '—' }}</dd>
              <dt>交付地点</dt>
              <dd>{{ project.deliverySite || '—' }}</dd>
              <dt>联系备注</dt>
              <dd>{{ project.contactNote || '—' }}</dd>
            </dl>
          </section>
        </div>
      </section>
    </template>
  </div>
</template>
