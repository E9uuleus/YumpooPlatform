<script setup lang="ts">
import { readCsrfToken, type Project, type WorkItemLabelColorToken } from '@yumpoo/api-client'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { projectsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { useSession } from '../../composables/useSession'
import InlineProblem from '../InlineProblem.vue'
import YpAssignee from '../yp/YpAssignee.vue'
import YpStatusTag from '../yp/YpStatusTag.vue'
import { DEFAULT_PROJECT_STRUCTURE } from './defaultProjectStructure'
import StructureColorPill from './StructureColorPill.vue'
import { workItemLabelColorValue } from './workItemLabelColors'

type StructureKind = 'categories' | 'statuses' | 'priorities'
type StructureColors = Record<StructureKind, Record<string, WorkItemLabelColorToken>>

const STRUCTURE_ROWS: { kind: StructureKind; label: string }[] = [
  { kind: 'categories', label: '类别' },
  { kind: 'statuses', label: '状态' },
  { kind: 'priorities', label: '优先级' },
]
/** Sample rows for the preview: they cover every category and priority and the common statuses. */
const PREVIEW_ROWS = [
  { title: '梳理需求范围', category: 'REQUIREMENTS', status: 'IN_PROGRESS', priority: 'HIGH' },
  { title: '搭建开发环境', category: 'TASKS', status: 'DONE', priority: 'MEDIUM' },
  { title: '修复登录跳转', category: 'DEFECTS', status: 'STUCK', priority: 'URGENT' },
  { title: '编写验收用例', category: 'TASKS', status: 'NOT_STARTED', priority: 'LOW' },
] as const

function defaultColors(): StructureColors {
  const colors = (items: readonly { code: string; color: string }[]) =>
    Object.fromEntries(items.map(item => [item.code, item.color as WorkItemLabelColorToken]))
  return {
    categories: colors(DEFAULT_PROJECT_STRUCTURE.categories),
    statuses: colors(DEFAULT_PROJECT_STRUCTURE.statuses),
    priorities: colors(DEFAULT_PROJECT_STRUCTURE.priorities),
  }
}
const DEFAULT_COLORS = JSON.stringify(defaultColors())

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; created: [project: Project] }>()
const session = useSession()
const form = reactive({ name: '', description: '' })
const colors = ref<StructureColors>(defaultColors())
const formRef = ref<FormInstance>()
const nameInput = ref<InstanceType<typeof ElInput>>()
const submitting = ref(false)
const problem = ref<ApiProblem>()
const topProblem = computed(() => problem.value?.kind === 'response'
  && problem.value.status === 422
  && problem.value.error.fieldErrors.some(({ field }) => field === 'name' || field === 'description')
  ? undefined : problem.value)
const valid = computed(() => form.name.trim().length > 0 && form.name.trim().length <= 80 && form.description.length <= 500)
const colorsChanged = computed(() => JSON.stringify(colors.value) !== DEFAULT_COLORS)
const dirty = computed(() => Boolean(form.name || form.description) || colorsChanged.value)
const user = computed(() => session.authentication.value?.user)
const previewName = computed(() => form.name.trim() || '未命名项目')
const previewDescription = computed(() => form.description.trim())
const initial = computed(() => Array.from(form.name.trim())[0] || '项')
let attempt: { body: string; key: string } | undefined
let opener: HTMLElement | undefined
const rules: FormRules = {
  name: [{ validator: (_rule, value: string, callback) => callback(!value.trim() ? new Error('请输入项目名称') : value.trim().length > 80 ? new Error('项目名称最多 80 个字符') : undefined), trigger: 'blur' }],
  description: [{ max: 500, message: '描述最多 500 字', trigger: 'blur' }],
}
function labelsOf(kind: StructureKind): readonly { code: string; name: string }[] {
  return DEFAULT_PROJECT_STRUCTURE[kind]
}
function labelName(kind: StructureKind, code: string): string {
  return labelsOf(kind).find(item => item.code === code)?.name ?? code
}
function labelColor(kind: StructureKind, code: string): string {
  return workItemLabelColorValue(colors.value[kind][code])
}
function fieldError(field: string): string {
  return problem.value?.kind === 'response' ? problem.value.error.fieldErrors.find(item => item.field === field)?.message ?? '' : ''
}
watch(() => props.modelValue, open => {
  if (!open) return
  Object.assign(form, { name: '', description: '' })
  colors.value = defaultColors()
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
  const request = { name: form.name.trim(), description: form.description.trim() || null, initialColors: colors.value }
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
    width="min(880px, calc(100vw - 32px))"
    top="8vh"
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
    </template>
    <inline-problem
      v-if="topProblem"
      :problem="topProblem"
    />
    <div class="project-create-dialog__layout">
      <el-form
        ref="formRef"
        class="project-create-dialog__form"
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
          <div class="project-create-dialog__name">
            <span
              class="project-create-dialog__tile"
              aria-hidden="true"
            >{{ initial }}</span>
            <el-input
              ref="nameInput"
              v-model="form.name"
              class="project-create-dialog__name-input"
              maxlength="80"
              show-word-limit
              placeholder="例如：研发门户升级"
              aria-label="项目名称"
            />
          </div>
        </el-form-item>
        <el-form-item
          label="项目描述（可选）"
          prop="description"
          :error="fieldError('description')"
        >
          <el-input
            v-model="form.description"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 6 }"
            maxlength="500"
            show-word-limit
            placeholder="简要介绍项目目标与协作内容"
            aria-label="项目描述"
          />
        </el-form-item>
        <section class="project-create-dialog__section">
          <h3>负责人</h3>
          <div class="project-create-dialog__owner">
            <yp-assignee
              :user-id="user?.id"
              :display-name="user?.displayName ?? '我'"
              :show-name="false"
              size="table"
            />
            <span>你（{{ user?.displayName ?? '我' }}）将成为负责人</span>
          </div>
        </section>
        <section
          class="project-create-dialog__section"
          aria-label="初始结构"
        >
          <div class="project-create-dialog__section-head">
            <h3>初始结构</h3>
            <button
              v-if="colorsChanged"
              type="button"
              class="project-create-dialog__reset"
              :disabled="submitting"
              @click="colors = defaultColors()"
            >
              恢复默认颜色
            </button>
          </div>
          <p class="project-create-dialog__hint">
            点击标签可调整颜色，创建后仍可在表格中修改。
          </p>
          <div
            v-for="row in STRUCTURE_ROWS"
            :key="row.kind"
            class="project-create-dialog__structure-row"
          >
            <span>{{ row.label }}</span>
            <div class="project-create-dialog__labels">
              <structure-color-pill
                v-for="label in labelsOf(row.kind)"
                :key="label.code"
                v-model:color="colors[row.kind][label.code]!"
                :name="label.name"
                :disabled="submitting"
              />
            </div>
          </div>
        </section>
      </el-form>
      <aside
        class="project-create-dialog__preview"
        aria-label="项目预览"
      >
        <p class="project-create-dialog__eyebrow">
          预览
        </p>
        <div class="project-preview">
          <div class="project-preview__head">
            <span
              class="project-preview__tile"
              aria-hidden="true"
            >{{ initial }}</span>
            <div class="project-preview__identity">
              <strong>{{ previewName }}</strong>
              <p :class="{ 'is-empty': !previewDescription }">
                {{ previewDescription || '暂无描述' }}
              </p>
            </div>
          </div>
          <div class="project-preview__meta">
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
          <div
            class="project-preview__tabs"
            aria-hidden="true"
          >
            <span class="is-active">工作项</span><span>统计</span><span>成员</span><span>动态</span>
          </div>
          <div
            class="project-preview__table"
            role="table"
            aria-label="示例工作项"
          >
            <div
              class="project-preview__row project-preview__row--head"
              role="row"
            >
              <span role="columnheader">工作项</span><span role="columnheader">类别</span><span role="columnheader">状态</span><span role="columnheader">优先级</span>
            </div>
            <div
              v-for="row in PREVIEW_ROWS"
              :key="row.title"
              class="project-preview__row"
              role="row"
            >
              <span
                class="project-preview__title"
                role="cell"
              >{{ row.title }}</span>
              <span
                class="project-preview__block"
                role="cell"
                :style="{ background: labelColor('categories', row.category) }"
              >{{ labelName('categories', row.category) }}</span>
              <span
                class="project-preview__block"
                role="cell"
                :style="{ background: labelColor('statuses', row.status) }"
              >{{ labelName('statuses', row.status) }}</span>
              <span
                class="project-preview__block"
                role="cell"
                :style="{ background: labelColor('priorities', row.priority) }"
              >{{ labelName('priorities', row.priority) }}</span>
            </div>
          </div>
        </div>
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
:global(.project-create-dialog) { display: flex; flex-direction: column; max-height: 84vh; padding: var(--yp-space-6); border-radius: var(--yp-radius-xl); background: var(--yp-bg-raised); box-shadow: var(--yp-shadow-overlay); }
:global(.project-create-dialog .el-dialog__body) { min-height: 0; overflow-y: auto; }
:global(.project-create-dialog .el-dialog__header), :global(.project-create-dialog .el-dialog__footer) { flex-shrink: 0; }
:global(.project-create-dialog .el-dialog__footer) { display: flex; align-items: center; justify-content: flex-end; gap: var(--yp-space-2); padding-top: var(--yp-space-5); border-top: 1px solid var(--yp-border-subtle); }
:global(.project-create-dialog .el-dialog__header h2) { margin: 0; color: var(--yp-text-primary); font-size: 18px; line-height: 1.4; }
:global(.project-create-dialog__layout) { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: var(--yp-space-6); }
:global(.project-create-dialog h3) { margin: 0 0 var(--yp-space-4); color: var(--yp-text-primary); font-size: 15px; }

/* Inputs: sunken fields with a soft focus halo. */
:global(.project-create-dialog__form .el-form-item__label) { margin-bottom: 6px; color: var(--yp-text-primary); font-size: 13px; font-weight: 600; line-height: 20px; }
:global(.project-create-dialog__form .el-input__wrapper), :global(.project-create-dialog__form .el-textarea__inner) { border-radius: var(--yp-radius-md); background: var(--yp-bg-sunken); box-shadow: 0 0 0 1px var(--yp-border-subtle) inset; transition: background var(--yp-motion-fast) var(--yp-ease-standard), box-shadow var(--yp-motion-fast) var(--yp-ease-standard); }
:global(.project-create-dialog__form .el-input__wrapper:hover), :global(.project-create-dialog__form .el-textarea__inner:hover) { box-shadow: 0 0 0 1px var(--yp-border-strong) inset; }
:global(.project-create-dialog__form .el-input__wrapper.is-focus), :global(.project-create-dialog__form .el-textarea__inner:focus) { background: var(--yp-bg-surface); box-shadow: 0 0 0 1px var(--yp-focus-ring) inset, 0 0 0 3px color-mix(in srgb, var(--yp-focus-ring) 22%, transparent); }
:global(.project-create-dialog__form .el-textarea__inner) { padding: 10px 12px; line-height: 1.6; }
:global(.project-create-dialog__name) { display: flex; width: 100%; align-items: center; gap: var(--yp-space-3); }
:global(.project-create-dialog__tile), :global(.project-preview__tile) { display: grid; flex: 0 0 44px; height: 44px; place-items: center; border-radius: var(--yp-radius-md); color: var(--yp-text-inverse); background: linear-gradient(135deg, var(--yp-action-primary), color-mix(in srgb, var(--yp-action-primary) 55%, var(--yp-status-purple))); font-size: 20px; font-weight: 600; }
:global(.project-create-dialog__name-input) { flex: 1; min-width: 0; }
:global(.project-create-dialog__name-input .el-input__wrapper) { height: 44px; padding: 0 12px; }
:global(.project-create-dialog__name-input .el-input__inner) { font-size: 16px; font-weight: 600; }

:global(.project-create-dialog__hint) { margin: 0; color: var(--yp-text-muted); font-size: 12px; line-height: 1.6; }
:global(.project-create-dialog__section) { margin-top: var(--yp-space-5); padding-top: var(--yp-space-4); border-top: 1px solid var(--yp-border-subtle); }
:global(.project-create-dialog__section h3) { margin-bottom: var(--yp-space-3); }
:global(.project-create-dialog__section-head) { display: flex; align-items: baseline; justify-content: space-between; gap: var(--yp-space-3); }
:global(.project-create-dialog__reset) { padding: 2px 6px; border: 0; border-radius: var(--yp-radius-sm); color: var(--yp-action-primary); background: transparent; font: 12px var(--yp-font-family); cursor: pointer; }
:global(.project-create-dialog__reset:hover) { background: var(--yp-bg-hover); }
:global(.project-create-dialog__owner) { display: flex; flex-wrap: wrap; align-items: center; gap: var(--yp-space-3); color: var(--yp-text-secondary); }
:global(.project-create-dialog__structure-row) { display: grid; grid-template-columns: 44px minmax(0, 1fr); align-items: center; gap: var(--yp-space-3); margin-top: var(--yp-space-3); color: var(--yp-text-secondary); font-size: 12px; }
:global(.project-create-dialog__labels) { display: flex; flex-wrap: wrap; gap: var(--yp-space-2); }

/* Preview: a scaled-down project page with the table's full-cell label blocks. */
:global(.project-create-dialog__preview) { align-self: start; position: sticky; top: 0; }
:global(.project-create-dialog__eyebrow) { margin: 0 0 var(--yp-space-2); color: var(--yp-text-muted); font-size: 12px; font-weight: 600; letter-spacing: .04em; }
:global(.project-preview) { overflow: hidden; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-lg); background: var(--yp-bg-surface); box-shadow: var(--yp-shadow-card); }
:global(.project-preview__head) { display: flex; align-items: flex-start; gap: var(--yp-space-3); padding: var(--yp-space-4) var(--yp-space-4) 0; }
:global(.project-preview__tile) { flex-basis: 36px; height: 36px; font-size: 16px; }
:global(.project-preview__identity) { min-width: 0; flex: 1; }
:global(.project-preview__identity strong) { display: block; overflow: hidden; color: var(--yp-text-primary); font: 600 16px/24px var(--yp-font-heading); text-overflow: ellipsis; white-space: nowrap; }
:global(.project-preview__identity p) { display: -webkit-box; margin: 2px 0 0; overflow: hidden; color: var(--yp-text-secondary); font-size: 12px; line-height: 18px; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow-wrap: anywhere; }
:global(.project-preview__identity p.is-empty) { color: var(--yp-text-muted); }
:global(.project-preview__meta) { display: flex; flex-wrap: wrap; align-items: center; gap: var(--yp-space-2); padding: var(--yp-space-3) var(--yp-space-4) 0; }
:global(.project-preview__tabs) { display: flex; gap: var(--yp-space-4); margin-top: var(--yp-space-3); padding: 0 var(--yp-space-4); border-bottom: 1px solid var(--yp-border-subtle); color: var(--yp-text-secondary); font-size: 12px; }
:global(.project-preview__tabs span) { padding: 6px 0; border-bottom: 2px solid transparent; }
:global(.project-preview__tabs .is-active) { border-bottom-color: var(--yp-action-primary); color: var(--yp-text-primary); font-weight: 600; }
:global(.project-preview__table) { margin: var(--yp-space-3); overflow: hidden; border: 1px solid var(--yp-monday-grid-border); border-radius: var(--yp-radius-sm); }
:global(.project-preview__row) { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) 46px 52px 40px; height: 30px; font-size: 11.5px; }
:global(.project-preview__row::before) { position: absolute; top: 0; bottom: 0; left: 0; width: 4px; background: var(--yp-status-blue); content: ''; }
:global(.project-preview__row + .project-preview__row) { border-top: 1px solid var(--yp-monday-grid-border); }
:global(.project-preview__row--head) { height: 26px; color: var(--yp-text-secondary); background: var(--yp-bg-sunken); }
:global(.project-preview__row--head::before) { display: none; }
:global(.project-preview__row > span) { display: flex; min-width: 0; align-items: center; justify-content: center; border-left: 1px solid var(--yp-monday-grid-border); }
:global(.project-preview__row > span:first-child) { justify-content: flex-start; padding-left: 12px; border-left: 0; }
:global(.project-preview__title) { overflow: hidden; color: var(--yp-text-primary); font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
:global(.project-preview__block) { color: var(--yp-text-inverse); font-weight: 600; white-space: nowrap; transition: background var(--yp-motion-fast) var(--yp-ease-standard); }
@media (max-width: 959.98px) {
  :global(.project-create-dialog__layout) { grid-template-columns: minmax(0, 1fr); }
  :global(.project-create-dialog__preview) { position: static; }
}
</style>
