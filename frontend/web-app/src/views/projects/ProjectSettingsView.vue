<script setup lang="ts">
import {
  ProjectLifecycle,
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
import { businessLabel } from '../../design-system/labels'

const route = useRoute()
const projectId = String(route.params.projectId)
const project = ref<ProjectDetail>()
const error = ref<ApiProblem>()
const saving = ref(false)
const activating = ref(false)
const formRef = ref<FormInstance>()
const baseline = ref('')
const form = reactive({
  name: '',
  description: '',
  customerName: '',
  customerReference: '',
  deliverySite: '',
  contactNote: '',
})
const rules: FormRules = {
  name: [{ required: true, whitespace: true, message: '请输入项目名称', trigger: 'blur' }],
}
const dirty = computed(() => Boolean(baseline.value && JSON.stringify(form) !== baseline.value))

function fill(next: ProjectDetail): void {
  form.name = next.name
  form.description = next.description ?? ''
  form.customerName = next.customerName ?? ''
  form.customerReference = next.customerReference ?? ''
  form.deliverySite = next.deliverySite ?? ''
  form.contactNote = next.contactNote ?? ''
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
    customerName: form.customerName.trim() || null,
    customerReference: form.customerReference.trim() || null,
    deliverySite: form.deliverySite.trim() || null,
    contactNote: form.contactNote.trim() || null,
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
  return value ? value.toLocaleString('zh-CN') : '—'
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
    await load(false)
  } catch (reason) {
    error.value = await toApiProblem(reason)
    await load(false)
  } finally {
    activating.value = false
  }
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
      description="保存时发送完整可变字段快照；权限和并发版本由服务端校验。"
    >
      <template #primary-action>
        <el-button
          v-if="project?.capabilities.canActivate"
          type="primary"
          :loading="activating"
          @click="activate"
        >
          激活 Project
        </el-button>
      </template>
    </project-workspace-header>
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <section class="project-settings-surface">
      <project-lifecycle-actions
        v-if="project"
        :project="project"
        @changed="load(false)"
        @problem="problem => error = problem"
      />
      <div v-if="project" class="project-definition-grid">
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
      <div class="project-section-heading">
        <div>
          <h2>设置</h2>
          <p>维护项目名称、说明和交付上下文。</p>
        </div>
      </div>
      <el-form
        ref="formRef"
        class="settings-form"
        label-position="top"
        :model="form"
        :rules="rules"
      >
        <section class="form-section">
          <h2>基本信息</h2>
          <el-form-item
            label="项目名称"
            prop="name"
          >
            <el-input
              v-model="form.name"
              maxlength="80"
            />
          </el-form-item>
          <el-form-item label="描述">
            <el-input
              v-model="form.description"
              type="textarea"
              maxlength="500"
              show-word-limit
            />
          </el-form-item>
        </section>
        <section class="form-section">
          <h2>交付上下文</h2>
          <div class="form-grid">
            <el-form-item label="客户名称">
              <el-input
                v-model="form.customerName"
                maxlength="160"
              />
            </el-form-item>
            <el-form-item label="客户参考号">
              <el-input
                v-model="form.customerReference"
                maxlength="80"
              />
            </el-form-item>
          </div>
          <el-form-item label="交付地点">
            <el-input
              v-model="form.deliverySite"
              maxlength="160"
            />
          </el-form-item>
          <el-form-item label="联系备注">
            <el-input
              v-model="form.contactNote"
              type="textarea"
              maxlength="500"
              show-word-limit
            />
          </el-form-item>
        </section>
        <p
          v-if="project && !project.capabilities.canUpdateSettings"
          class="actor-label"
        >
          当前访问模式不可修改项目设置。
        </p>
        <div class="action-row">
          <span
            v-if="dirty"
            class="muted-text"
          >存在未保存的更改</span>
          <el-button
            type="primary"
            :disabled="!project?.capabilities.canUpdateSettings || !dirty"
            :loading="saving"
            @click="save"
          >
            保存设置
          </el-button>
        </div>
      </el-form>
    </section>
  </div>
</template>
