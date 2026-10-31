<script setup lang="ts">
import YpSkeleton from '../../components/yp/YpSkeleton.vue'
import { usePageLoad } from '../../brand/page-progress'

import {
  readCsrfToken,
  type ProjectDetail,
  type ProjectUpdateRequest,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules,
} from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import { projectStatus } from '../../components/projects/projectStatus'
import ProjectLifecycleActions from '../../components/projects/ProjectLifecycleActions.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import ProjectConnectionsOverview from '../../components/projects/connect/ProjectConnectionsOverview.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import { projectColors } from '../../components/team/teamDashboardModel'
import { useSession } from '../../composables/useSession'
import { notifyProjectSettingsChanged } from '../../composables/projectSettingsEvents'
import { formatTimestamp } from '../../design-system/dates'
import { businessLabel } from '../../design-system/labels'

const pageLoad = usePageLoad()
const session = useSession()
const route = useRoute()
const projectId = String(route.params.projectId)
const project = ref<ProjectDetail>()
const mark = computed(() => project.value ? projectColors([project.value.id]).get(project.value.id) : undefined)
const error = ref<ApiProblem>()
const saving = ref(false)
const formRef = ref<FormInstance>()
const baseline = ref('')
const form = reactive({
  name: '',
  description: '',
})
const rules: FormRules = {
  name: [{ required: true, whitespace: true, message: '请输入项目名称', trigger: 'blur' }],
}
const dirty = computed(() => Boolean(baseline.value && JSON.stringify(form) !== baseline.value))

function fill(next: ProjectDetail): void {
  form.name = next.name
  form.description = next.description ?? ''
  baseline.value = JSON.stringify(form)
}

async function load(replaceDraft = true): Promise<void> {
  error.value = undefined
  try {
    const next = await projectsApi.getProject({ projectId })
    project.value = next
    if (replaceDraft) fill(next)
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

async function save(): Promise<void> {
  if (!project.value?.capabilities.canUpdateSettings) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  const snapshot: ProjectUpdateRequest = {
    name: form.name.trim(),
    description: form.description.trim() || null,
  }
  saving.value = true
  error.value = undefined
  try {
    project.value = await projectsApi.updateProject({
      projectId,
      xXSRFTOKEN: csrf,
      ifMatch: project.value.etag,
      projectUpdateRequest: snapshot,
    })
    fill(project.value)
    notifyProjectSettingsChanged(project.value)
    ElMessage.success('项目设置已保存')
  } catch (reason) {
    const problem = await toApiProblem(reason)
    error.value = problem
    if (isProblemStatus(problem, 412)) await load(false)
  } finally {
    saving.value = false
  }
}

function formatTime(value?: Date | null): string {
  return value ? formatTimestamp(value, session.authentication.value?.company.timezone ?? 'UTC') : '—'
}

onBeforeRouteLeave(async () => {
  if (!dirty.value || saving.value) return true
  try {
    await ElMessageBox.confirm('项目设置仍有未保存的更改。', '离开此页面？', {
      confirmButtonText: '放弃更改',
      cancelButtonText: '继续编辑',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
})

onMounted(() => pageLoad.track(load))
</script>

<template>
<div class="project-view-stack">
  <project-workspace-header
    section="settings"
    :project="project"
    title="项目设置"
    @changed="load(false)"
  />
  <inline-problem
    v-if="error"
    :problem="error"
  />
  <yp-skeleton
    v-if="!project && !error"
    variant="form"
    :rows="3"
    label="正在加载项目设置"
  />
  <div
    v-else-if="project"
    class="project-settings"
  >
    <section
      class="project-identity"
      aria-label="项目概要"
    >
      <span
        class="project-identity__mark"
        :style="{ '--mark': mark }"
        aria-hidden="true"
      >{{ project.name.slice(0, 1) }}</span>
      <div class="project-identity__main">
        <h2>{{ project.name }}</h2>
        <div class="project-identity__tags">
          <code title="用于工作项编号，不可修改">{{ project.code }}</code>
          <yp-status-tag
            domain="project-lifecycle"
            :status="projectStatus(project)"
            size="small"
          />
        </div>
      </div>
      <dl class="project-identity__facts">
        <div>
          <dt>负责人</dt>
          <dd>
            <yp-assignee
              :user-id="project.ownerUserId"
              :display-name="project.ownerDisplayName"
              size="table"
            />
          </dd>
        </div>
        <div><dt>访问</dt><dd>{{ businessLabel(project.actorAccess) }}</dd></div>
        <div><dt>创建</dt><dd>{{ formatTime(project.createdAt) }}</dd></div>
        <div><dt>更新</dt><dd>{{ formatTime(project.updatedAt) }}</dd></div>
        <div v-if="project.endedAt"><dt>结束</dt><dd>{{ formatTime(project.endedAt) }}</dd></div>
        <div v-if="project.archivedAt"><dt>归档</dt><dd>{{ formatTime(project.archivedAt) }}</dd></div>
      </dl>
    </section>
    <section
      class="project-settings__section"
      aria-labelledby="project-basic-title"
    >
      <h2 id="project-basic-title">
        基本信息
      </h2>
      <el-form
        ref="formRef"
        label-position="top"
        :model="form"
        :rules="rules"
        :disabled="saving || !project.capabilities.canUpdateSettings"
        @submit.prevent="save"
      >
        <el-form-item
          label="项目名称"
          prop="name"
        >
          <el-input
            v-model="form.name"
            maxlength="80"
            show-word-limit
            aria-label="项目名称"
          />
        </el-form-item>
        <el-form-item label="项目描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            aria-label="项目描述"
          />
        </el-form-item>
      </el-form>
      <div class="action-row">
        <span
          v-if="dirty"
          class="muted-text"
        >存在未保存的更改</span><el-button
          type="primary"
          :disabled="!project.capabilities.canUpdateSettings || !dirty || saving"
          :loading="saving"
          @click="save"
        >
          保存设置
        </el-button>
      </div>
    </section>
    <section
      class="project-settings__section"
      aria-labelledby="project-connections-title"
    >
      <h2 id="project-connections-title">
        连接
      </h2>
      <project-connections-overview :project-id="projectId" />
    </section>
    <project-lifecycle-actions
      :project="project"
      @changed="load(false)"
      @problem="problem => error = problem"
    />
  </div>
</div>
</template>

<style scoped>
.project-settings { display: grid; max-width: 880px; gap: var(--yp-space-8); }
.project-identity { display: grid; grid-template-columns: 48px minmax(0, 1fr); align-items: center; gap: var(--yp-space-4); }
.project-identity__mark { display: grid; width: 48px; height: 48px; place-items: center; border-radius: var(--yp-radius-md);
  background: var(--mark); color: var(--yp-on-solid); font: 600 20px/1 var(--yp-font-heading); }
.project-identity__main { display: grid; min-width: 0; gap: var(--yp-space-1); }
.project-identity h2 { margin: 0; font-size: var(--yp-type-section-title-size); font-weight: 600; line-height: var(--yp-type-section-title-line); overflow-wrap: anywhere; }
.project-identity__tags { display: flex; flex-wrap: wrap; align-items: center; gap: var(--yp-space-2); }
.project-identity__tags code { padding: 2px var(--yp-space-2); border-radius: var(--yp-radius-sm); background: var(--yp-fill-neutral); color: var(--yp-text-secondary); font: 500 12px/18px var(--yp-font-mono); }
.project-identity__facts { display: grid; grid-column: 1 / -1; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: var(--yp-space-3) var(--yp-space-6); margin: 0; }
.project-identity__facts > div { display: grid; min-width: 0; gap: 2px; }
.project-identity__facts dt { color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.project-identity__facts dd { margin: 0; min-width: 0; color: var(--yp-text-primary); }
.project-settings__section h2 { margin: 0 0 var(--yp-space-4); font-size: var(--yp-type-card-title-size); font-weight: 600; }
@media (max-width: 720px) { .project-settings { gap: var(--yp-space-6); } }
</style>
