<script setup lang="ts">
import { readCsrfToken, type Project } from '@yumpoo/api-client'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { projectsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { useSession } from '../../composables/useSession'
import InlineProblem from '../InlineProblem.vue'
import YpAssignee from '../yp/YpAssignee.vue'
import YpStatusTag from '../yp/YpStatusTag.vue'
import { DEFAULT_PROJECT_STRUCTURE } from './defaultProjectStructure'
import { workItemLabelColorValue } from './workItemLabelColors'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; created: [project: Project] }>()
const session = useSession()
const form = reactive({ name: '', description: '' })
const formRef = ref<FormInstance>()
const nameInput = ref<InstanceType<typeof ElInput>>()
const submitting = ref(false)
const problem = ref<ApiProblem>()
const valid = computed(() => form.name.trim().length > 0 && form.name.trim().length <= 80 && form.description.length <= 500)
const dirty = computed(() => Boolean(form.name || form.description))
const user = computed(() => session.authentication.value?.user)
const previewName = computed(() => form.name.trim() || '未命名项目')
const previewDescription = computed(() => form.description.trim() || '暂无描述')
const previewInitial = computed(() => Array.from(form.name.trim())[0] || '项')
let attempt: { body: string; key: string } | undefined
let opener: HTMLElement | undefined
const rules: FormRules = {
  name: [{ validator: (_rule, value: string, callback) => callback(!value.trim() ? new Error('请输入项目名称') : value.trim().length > 80 ? new Error('项目名称最多 80 个字符') : undefined), trigger: 'blur' }],
  description: [{ max: 500, message: '描述最多 500 字', trigger: 'blur' }],
}
function fieldError(field: string): string {
  return problem.value?.kind === 'response' ? problem.value.error.fieldErrors.find(item => item.field === field)?.message ?? '' : ''
}
watch(() => props.modelValue, open => {
  if (!open) return
  Object.assign(form, { name: '', description: '' })
  problem.value = undefined
  attempt = undefined
  formRef.value?.clearValidate()
  opener = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
}, { immediate: true })
async function focusName(event?: Event): Promise<void> {
  event?.preventDefault()
  await nextTick()
  await nextTick()
  nameInput.value?.focus()
}
function restoreFocus(event?: Event): void {
  event?.preventDefault()
  opener?.focus({ preventScroll: true })
}
async function close(done?: () => void): Promise<void> {
  if (submitting.value) return
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('输入的内容将丢失。', '放弃创建？', {
        confirmButtonText: '放弃', cancelButtonText: '继续编辑', type: 'warning',
      })
    } catch { return }
  }
  done?.()
  emit('update:modelValue', false)
}
async function submit(): Promise<void> {
  if (submitting.value || !valid.value) return
  try { await formRef.value?.validate() } catch { return }
  const token = readCsrfToken()
  if (!token) { problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  const request = { name: form.name.trim(), description: form.description.trim() || null }
  const body = JSON.stringify(request)
  if (attempt?.body !== body) attempt = { body, key: crypto.randomUUID() }
  submitting.value = true
  problem.value = undefined
  try {
    const project = await projectsApi.createProject({ xXSRFTOKEN: token, idempotencyKey: attempt.key, projectCreateRequest: request })
    emit('update:modelValue', false)
    emit('created', project)
  } catch (reason) { problem.value = await toApiProblem(reason) }
  finally { submitting.value = false }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="project-create-dialog"
    title="创建项目"
    width="min(800px, calc(100vw - 32px))"
    top="10vh"
    append-to-body
    destroy-on-close
    modal-class="project-create-overlay"
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    :before-close="close"
    @open-auto-focus="focusName"
    @close-auto-focus="restoreFocus"
  >
    <template #header="{ titleId }">
      <h2 :id="titleId">
        创建项目
      </h2>
      <p class="project-create-dialog__intro">
        项目是成员、权限和工作项的边界。创建后你将成为项目负责人。
      </p>
    </template>
    <inline-problem
      v-if="problem"
      :problem="problem"
    />
    <div class="project-create-dialog__layout">
      <el-form
        ref="formRef"
        label-position="top"
        :model="form"
        :rules="rules"
        :disabled="submitting"
        @submit.prevent="submit"
      >
        <h3>基本信息</h3>
        <el-form-item
          label="项目名称"
          prop="name"
          :error="fieldError('name')"
          required
        >
          <el-input
            ref="nameInput"
            v-model="form.name"
            maxlength="80"
            show-word-limit
            placeholder="例如：研发门户升级"
            aria-label="项目名称"
          />
        </el-form-item>
        <el-form-item
          label="项目描述（可选）"
          prop="description"
          :error="fieldError('description')"
        >
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="简要介绍项目目标与协作内容"
            aria-label="项目描述"
          />
        </el-form-item>
        <p class="project-create-dialog__hint">
          创建后可在项目设置中修改名称与描述。
        </p>
        <section class="project-create-dialog__section">
          <h3>负责人</h3>
          <div class="project-create-dialog__owner">
            <yp-assignee
              :user-id="user?.id"
              :display-name="user?.displayName ?? '我'"
              size="table"
            />
            <span>你将成为负责人</span>
          </div>
        </section>
        <section
          class="project-create-dialog__section"
          aria-label="初始结构"
        >
          <h3>初始结构</h3>
          <p class="project-create-dialog__hint">
            所有项目使用相同的初始结构，创建后可在表格中调整。
          </p>
          <div class="project-create-dialog__structure-row">
            <span>类别</span>
            <div class="project-create-dialog__labels">
              <span
                v-for="category in DEFAULT_PROJECT_STRUCTURE.categories"
                :key="category.code"
              ><i :style="{ background: workItemLabelColorValue(category.color) }" />{{ category.name }}</span>
            </div>
          </div>
          <div class="project-create-dialog__structure-row">
            <span>状态</span>
            <div class="project-create-dialog__labels">
              <span
                v-for="status in DEFAULT_PROJECT_STRUCTURE.statuses"
                :key="status.code"
              ><i :style="{ background: workItemLabelColorValue(status.color) }" />{{ status.name }}</span>
            </div>
          </div>
          <div class="project-create-dialog__structure-row">
            <span>优先级</span>
            <div class="project-create-dialog__labels">
              <span
                v-for="priority in DEFAULT_PROJECT_STRUCTURE.priorities"
                :key="priority.code"
              ><i :style="{ background: workItemLabelColorValue(priority.color) }" />{{ priority.name }}</span>
            </div>
          </div>
        </section>
      </el-form>
      <aside
        class="project-create-dialog__preview"
        aria-label="默认项目结构预览"
      >
        <p>预览</p>
        <div class="project-create-dialog__identity">
          <span
            class="project-create-dialog__initial-avatar"
            aria-hidden="true"
          >{{ previewInitial }}</span>
          <div class="project-create-dialog__identity-text">
            <strong>{{ previewName }}</strong>
            <p>{{ previewDescription }}</p>
            <div class="project-create-dialog__preview-meta">
              <yp-status-tag
                domain="project-lifecycle"
                status="ACTIVE"
                size="small"
              />
              <yp-assignee
                :user-id="user?.id"
                :display-name="user?.displayName ?? '我'"
                size="table"
              />
            </div>
          </div>
        </div>
        <div class="project-create-dialog__table">
          <div class="project-create-dialog__row project-create-dialog__row--heading">
            <span>工作项名称</span><span>状态</span><span>类别</span>
          </div>
          <div
            v-for="(category, index) in DEFAULT_PROJECT_STRUCTURE.categories"
            :key="category.code"
            class="project-create-dialog__row"
          >
            <span>示例{{ category.name }}</span><span
              class="project-create-dialog__sample-status"
              :style="{ background: workItemLabelColorValue(DEFAULT_PROJECT_STRUCTURE.statuses[index]!.color) }"
            >{{ DEFAULT_PROJECT_STRUCTURE.statuses[index]!.name }}</span>
            <span class="project-create-dialog__category"><i :style="{ background: workItemLabelColorValue(category.color) }" />{{ category.name }}</span>
          </div>
        </div>
        <p class="project-create-dialog__preview-note">
          示例行仅为示意，不会写入项目；项目编码创建后由系统生成。
        </p>
      </aside>
    </div>
    <template #footer>
      <el-button
        :disabled="submitting"
        @click="close()"
      >
        取消
      </el-button><el-button
        type="primary"
        :disabled="!valid || submitting"
        :loading="submitting"
        @click="submit"
      >
        创建项目
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.project-create-overlay) { background: var(--yp-overlay); }
:global(.project-create-dialog) { display: flex; flex-direction: column; max-height: 80vh; padding: var(--yp-space-6); border-radius: var(--yp-radius-xl); background: var(--yp-bg-raised); box-shadow: var(--yp-shadow-overlay); }
:global(.project-create-dialog .el-dialog__body) { min-height: 0; overflow-y: auto; }
:global(.project-create-dialog .el-dialog__header), :global(.project-create-dialog .el-dialog__footer) { flex-shrink: 0; }
:global(.project-create-dialog .el-dialog__footer) { border-top: 1px solid var(--yp-border-subtle); padding-top: var(--yp-space-5); display: flex; align-items: center; justify-content: space-between; }
:global(.project-create-dialog .el-dialog__header h2) { margin: 0; font-size: 18px; line-height: 1.4; color: var(--yp-text-primary); }
:global(.project-create-dialog__intro) { margin: var(--yp-space-2) 0 0; color: var(--yp-text-secondary); font-size: 13px; font-weight: 400; line-height: 1.6; }
:global(.project-create-dialog__layout) { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: var(--yp-space-6); }
:global(.project-create-dialog h3) { margin: 0 0 var(--yp-space-5); color: var(--yp-text-primary); font-size: 15px; }
:global(.project-create-dialog__owner) { display: flex; flex-wrap: wrap; align-items: center; gap: var(--yp-space-3); color: var(--yp-text-secondary); }
:global(.project-create-dialog__hint) { font-size: 12px; color: var(--yp-text-muted); }
:global(.project-create-dialog__section) { margin-top: var(--yp-space-5); padding-top: var(--yp-space-4); border-top: 1px solid var(--yp-border-subtle); }
:global(.project-create-dialog__section h3) { margin-bottom: var(--yp-space-3); }
:global(.project-create-dialog__structure-row) { display: grid; grid-template-columns: 44px minmax(0, 1fr); align-items: baseline; gap: var(--yp-space-3); margin-top: var(--yp-space-3); font-size: 12px; color: var(--yp-text-secondary); }
:global(.project-create-dialog__preview) { align-self: start; padding: var(--yp-space-4); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-sunken); }
:global(.project-create-dialog__preview p) { margin: 0 0 var(--yp-space-4); color: var(--yp-text-secondary); font-size: 12px; line-height: 1.6; }
:global(.project-create-dialog__identity) { display: flex; align-items: flex-start; gap: var(--yp-space-3); padding: var(--yp-space-3); background: var(--yp-bg-surface); border: 1px solid var(--yp-border-subtle); border-bottom: 0; border-radius: var(--yp-radius-sm) var(--yp-radius-sm) 0 0; }
:global(.project-create-dialog__identity-text) { min-width: 0; flex: 1; overflow-wrap: anywhere; }
:global(.project-create-dialog__identity-text strong) { color: var(--yp-text-primary); }
:global(.project-create-dialog__identity-text p) { margin: var(--yp-space-1) 0 var(--yp-space-2); max-height: 3.2em; overflow: hidden; }
:global(.project-create-dialog__initial-avatar) { display: grid; flex: 0 0 40px; height: 40px; place-items: center; border-radius: var(--yp-radius-sm); color: var(--yp-link); background: var(--yp-bg-selected); font-size: 20px; font-weight: 600; }
:global(.project-create-dialog__preview-meta) { display: flex; flex-wrap: wrap; align-items: center; gap: var(--yp-space-2); }
:global(.project-create-dialog__preview .project-create-dialog__preview-note) { margin: var(--yp-space-3) 0 0; }
:global(.project-create-dialog__table) { border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-sm); overflow: hidden; background: var(--yp-bg-surface); }
:global(.project-create-dialog__row) { display: grid; grid-template-columns: minmax(0, 1fr) 62px 52px; gap: var(--yp-space-1); align-items: center; padding: var(--yp-space-3) var(--yp-space-2); font-size: 12px; }
:global(.project-create-dialog__row + .project-create-dialog__row) { border-top: 1px solid var(--yp-border-subtle); }
:global(.project-create-dialog__row--heading) { background: var(--yp-bg-sunken); color: var(--yp-text-secondary); }
:global(.project-create-dialog__sample-status) { padding: var(--yp-space-1); border-radius: var(--yp-radius-sm); text-align: center; color: var(--yp-text-inverse); }
:global(.project-create-dialog__category), :global(.project-create-dialog__labels span) { display: inline-flex; align-items: center; gap: var(--yp-space-1); white-space: nowrap; }
:global(.project-create-dialog i) { width: 8px; height: 8px; flex: 0 0 8px; border-radius: 50%; }
:global(.project-create-dialog__labels) { display: flex; flex-wrap: wrap; gap: var(--yp-space-2) var(--yp-space-3); font-size: 12px; }
@media (max-width: 959.98px) { :global(.project-create-dialog__layout) { grid-template-columns: minmax(0, 1fr); } }
</style>
