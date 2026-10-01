<script setup lang="ts">
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
import ProjectLifecycleActions from '../../components/projects/ProjectLifecycleActions.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import { useSession } from '../../composables/useSession'
import { formatTimestamp } from '../../design-system/dates'
import { businessLabel } from '../../design-system/labels'

const session = useSession()
const route = useRoute()
const projectId = String(route.params.projectId)
const project = ref<ProjectDetail>()
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

onMounted(load)
</script>

<template>
  <div class="project-view-stack">
    <project-workspace-header
      section="settings"
      :project="project"
      title="项目设置"
      description="管理项目基本信息与协作设置。"
    />
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <div
      v-if="project"
      class="unified-project-settings"
    >
      <section class="unified-project-settings__section">
        <h2>基本信息</h2>
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
          <el-form-item label="项目编码">
            <div class="unified-project-settings__code">
              <code>{{ project.code }}</code><span>用于工作项编号，不可修改</span>
            </div>
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
      <section class="unified-project-settings__section">
        <h2>项目信息</h2>
        <dl class="unified-project-settings__facts">
          <dt>访问模式</dt><dd>{{ businessLabel(project.actorAccess) }}</dd>
          <dt>负责人</dt><dd>
            <yp-assignee
              :user-id="project.ownerUserId"
              :display-name="project.ownerDisplayName"
              size="table"
            />
          </dd>
          <dt>创建时间</dt><dd>{{ formatTime(project.createdAt) }}</dd>
          <dt>更新时间</dt><dd>{{ formatTime(project.updatedAt) }}</dd>
          <template v-if="project.archivedAt">
            <dt>归档时间</dt><dd>{{ formatTime(project.archivedAt) }}</dd>
          </template>
        </dl>
      </section>
      <section
        class="unified-project-settings__section"
        aria-labelledby="project-connections-title"
      >
        <h2 id="project-connections-title">
          连接
        </h2>
        <div class="unified-project-settings__empty">
          还没有连接。在工作项表格右上角「+」中添加连接列。
        </div>
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
.unified-project-settings { display: grid; gap: var(--yp-space-6); width: 100%; max-width: 760px; }
.unified-project-settings__section { padding: var(--yp-space-6); background: var(--yp-bg-surface); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); }
.unified-project-settings h2 { margin: 0 0 var(--yp-space-5); font-size: 16px; font-weight: 600; }
.unified-project-settings__code { display: flex; align-items: center; flex-wrap: wrap; gap: var(--yp-space-3); }
.unified-project-settings__code code { color: var(--yp-text-primary); background: var(--yp-bg-sunken); padding: 2px var(--yp-space-2); border-radius: var(--yp-radius-sm); }
.unified-project-settings__code span, .unified-project-settings__empty { color: var(--yp-text-secondary); font-size: 13px; }
.unified-project-settings__facts { display: grid; grid-template-columns: 100px minmax(0, 1fr); align-items: center; gap: var(--yp-space-4); margin: 0; font-size: 13px; }
.unified-project-settings__facts dt { color: var(--yp-text-secondary); }
.unified-project-settings__facts dd { margin: 0; }
.unified-project-settings__empty { padding: var(--yp-space-5); border: 1px dashed var(--yp-border-default); border-radius: var(--yp-radius-sm); background: var(--yp-bg-sunken); line-height: 1.7; }
@media (max-width: 720px) { .unified-project-settings__section { padding: var(--yp-space-4); } }
</style>
