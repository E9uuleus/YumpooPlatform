<script setup lang="ts">
import { Check, Close as CloseIcon, Search } from '@element-plus/icons-vue'
import {
  ProjectMemberCandidateMembershipStatusEnum,
  readCsrfToken,
  type ProjectMemberCandidate,
} from '@yumpoo/api-client'
import { ElButton, ElDialog, ElIcon, ElInput, ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { projectsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../InlineProblem.vue'
import YpAssignee from '../yp/YpAssignee.vue'

const props = defineProps<{
  modelValue: boolean
  projectId: string
  projectEtag: string
  reasonRequired: boolean
  canReassignOwner: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  added: [count: number]
  reassigned: []
}>()

const query = ref('')
const candidates = ref<ProjectMemberCandidate[]>([])
const selected = ref<ProjectMemberCandidate[]>([])
const reason = ref('')
const searching = ref(false)
const searchFailed = ref(false)
const submitting = ref(false)
const problem = ref<ApiProblem>()
const searchInput = ref<InstanceType<typeof ElInput>>()
const attempts = new Map<string, { body: string; key: string }>()
let timer: ReturnType<typeof setTimeout> | undefined
let controller: AbortController | undefined

const trimmedReason = computed(() => reason.value.trim())
const reasonValid = computed(() => !props.reasonRequired
  || (trimmedReason.value.length >= 10 && trimmedReason.value.length <= 500))
const canSubmit = computed(() => selected.value.length > 0 && reasonValid.value && !submitting.value)
const canMakeOwner = computed(() => props.canReassignOwner && selected.value.length === 1 && !submitting.value)

function stateHint(candidate: ProjectMemberCandidate): string {
  if (candidate.owner) return '项目负责人'
  if (candidate.membershipStatus === ProjectMemberCandidateMembershipStatusEnum.Active) return '已是成员'
  if (candidate.membershipStatus === ProjectMemberCandidateMembershipStatusEnum.Removed) return '曾被移出，将重新加入'
  return ''
}

function selectable(candidate: ProjectMemberCandidate): boolean {
  return !candidate.owner && candidate.membershipStatus !== ProjectMemberCandidateMembershipStatusEnum.Active
}

function isSelected(candidate: ProjectMemberCandidate): boolean {
  return selected.value.some(item => item.userId === candidate.userId)
}

function toggle(candidate: ProjectMemberCandidate): void {
  if (submitting.value || !selectable(candidate)) return
  selected.value = isSelected(candidate)
    ? selected.value.filter(item => item.userId !== candidate.userId)
    : [...selected.value, candidate]
}

async function search(): Promise<void> {
  controller?.abort()
  const name = query.value.trim()
  searchFailed.value = false
  if (!name) {
    candidates.value = []
    searching.value = false
    return
  }
  controller = new AbortController()
  const { signal } = controller
  searching.value = true
  try {
    const page = await projectsApi.listProjectMemberCandidates({ projectId: props.projectId, name, page: 0, size: 20 }, { signal })
    if (signal.aborted) return
    candidates.value = page.items
    selected.value = selected.value
      .map(item => page.items.find(candidate => candidate.userId === item.userId) ?? item)
      .filter(selectable)
  } catch {
    if (signal.aborted) return
    candidates.value = []
    searchFailed.value = true
  } finally {
    if (!signal.aborted) searching.value = false
  }
}

/** 失败后按姓名逐个刷新已选候选人，确保重新加入使用最新的成员 ETag，且不依赖当前搜索词。 */
async function refreshSelected(): Promise<void> {
  try {
    const refreshed = await Promise.all(selected.value.map(async item => {
      const page = await projectsApi.listProjectMemberCandidates({
        projectId: props.projectId,
        name: item.displayName,
        page: 0,
        size: 20,
      })
      return page.items.find(candidate => candidate.userId === item.userId) ?? item
    }))
    selected.value = refreshed.filter(selectable)
  } catch {
    // 刷新失败时保留提交失败的错误提示，已选快照不变。
  }
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  const csrf = readCsrfToken()
  if (!csrf) {
    problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  submitting.value = true
  problem.value = undefined
  const failed: ProjectMemberCandidate[] = []
  let added = 0
  for (const candidate of selected.value) {
    const request = { userId: candidate.userId, reason: trimmedReason.value || null }
    const body = JSON.stringify(request)
    const previous = attempts.get(candidate.userId)
    const key = previous?.body === body ? previous.key : crypto.randomUUID()
    attempts.set(candidate.userId, { body, key })
    try {
      await projectsApi.addProjectMember({
        projectId: props.projectId,
        xXSRFTOKEN: csrf,
        idempotencyKey: key,
        projectMemberAddRequest: request,
        ...(candidate.membershipEtag ? { ifMatch: candidate.membershipEtag } : {}),
      })
      added += 1
      attempts.delete(candidate.userId)
    } catch (reasonValue) {
      failed.push(candidate)
      problem.value = await toApiProblem(reasonValue)
    }
  }
  selected.value = failed
  if (failed.length) await refreshSelected()
  submitting.value = false
  if (added) emit('added', added)
  if (failed.length) return
  ElMessage.success(`已添加 ${added} 位成员`)
  emit('update:modelValue', false)
}

async function makeOwner(): Promise<void> {
  const candidate = selected.value[0]
  if (!canMakeOwner.value || !candidate) return
  let ownerReason: string
  try {
    ownerReason = (await ElMessageBox.prompt(
      `将负责人转交给「${candidate.displayName}」；此操作必须记录治理理由。`,
      '设为负责人',
      {
        inputValue: trimmedReason.value,
        inputPlaceholder: '请输入 10～500 字治理理由',
        inputPattern: /^(?=[\s\S]{10,500}$)(?=\s*\S)/,
        inputErrorMessage: '治理理由必须为 10～500 字',
      },
    )).value.trim()
  } catch {
    return
  }
  const csrf = readCsrfToken()
  if (!csrf) {
    problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  submitting.value = true
  problem.value = undefined
  try {
    await projectsApi.reassignProjectOwner({
      projectId: props.projectId,
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      ifMatch: props.projectEtag,
      projectOwnerReassignmentRequest: { newOwnerUserId: candidate.userId, reason: ownerReason },
    })
    ElMessage.success('负责人已转交')
    emit('reassigned')
    emit('update:modelValue', false)
  } catch (reasonValue) {
    problem.value = await toApiProblem(reasonValue)
  } finally {
    submitting.value = false
  }
}

function close(done?: () => void): void {
  if (submitting.value) return
  done?.()
  emit('update:modelValue', false)
}

watch(() => props.modelValue, open => {
  if (!open) return
  clearTimeout(timer)
  controller?.abort()
  query.value = ''
  candidates.value = []
  selected.value = []
  reason.value = ''
  problem.value = undefined
  searchFailed.value = false
  attempts.clear()
}, { immediate: true })

watch(query, value => {
  clearTimeout(timer)
  searching.value = Boolean(value.trim())
  timer = setTimeout(() => { void search() }, 250)
})

onBeforeUnmount(() => {
  clearTimeout(timer)
  controller?.abort()
})
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="project-member-add-dialog"
    modal-class="project-member-add-overlay"
    width="min(560px, calc(100vw - 32px))"
    align-center
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    :before-close="close"
    @opened="searchInput?.focus()"
  >
    <template #header="{ titleId }">
      <h2
        :id="titleId"
        class="member-add__title"
      >
        添加项目成员
      </h2>
      <p class="member-add__intro">
        成员可以查看并协作此项目中的全部工作项。
      </p>
    </template>
    <inline-problem
      v-if="problem"
      :problem="problem"
    />
    <div class="member-add">
      <div
        v-if="selected.length"
        class="member-add__selected"
        aria-label="已选择的成员"
      >
        <span
          v-for="candidate in selected"
          :key="candidate.userId"
          class="member-add__chip"
        >
          <yp-assignee
            :user-id="candidate.userId"
            :display-name="candidate.displayName"
            size="table"
            tooltip-disabled
          />
          <button
            type="button"
            class="member-add__chip-remove"
            :disabled="submitting"
            :aria-label="`取消选择${candidate.displayName}`"
            @click="toggle(candidate)"
          >
            <el-icon><close-icon /></el-icon>
          </button>
        </span>
      </div>
      <el-input
        ref="searchInput"
        v-model="query"
        :prefix-icon="Search"
        clearable
        placeholder="输入姓名搜索同企业成员"
        aria-label="搜索同企业成员"
        :disabled="submitting"
      />
      <div
        class="member-add__results"
        role="listbox"
        aria-label="候选成员"
        aria-multiselectable="true"
        :aria-busy="searching"
      >
        <p
          v-if="!query.trim()"
          class="member-add__hint"
        >
          输入姓名开始搜索，仅显示在职且账号已启用的同企业成员。
        </p>
        <template v-else>
          <button
            v-for="candidate in candidates"
            :key="candidate.userId"
            type="button"
            role="option"
            class="member-add__option"
            :class="{ 'is-selected': isSelected(candidate) }"
            :aria-selected="isSelected(candidate)"
            :disabled="submitting || !selectable(candidate)"
            @click="toggle(candidate)"
          >
            <yp-assignee
              :user-id="candidate.userId"
              :display-name="candidate.displayName"
              tooltip-disabled
            />
            <span
              v-if="stateHint(candidate)"
              class="member-add__state"
            >{{ stateHint(candidate) }}</span>
            <el-icon
              v-if="isSelected(candidate)"
              class="member-add__check"
              aria-hidden="true"
            >
              <check />
            </el-icon>
          </button>
          <p
            v-if="searchFailed"
            class="member-add__hint"
            role="alert"
          >
            成员搜索失败，请稍后重试
          </p>
          <p
            v-else-if="!searching && !candidates.length"
            class="member-add__hint"
            role="status"
          >
            没有找到匹配的成员
          </p>
        </template>
      </div>
      <div
        v-if="reasonRequired"
        class="member-add__reason"
      >
        <span id="member-add-reason-label">治理理由</span>
        <el-input
          v-model="reason"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="企业管理员添加成员需填写 10～500 字理由"
          aria-labelledby="member-add-reason-label"
          :disabled="submitting"
        />
      </div>
    </div>
    <template #footer>
      <span class="member-add__footer-start">
        <span class="member-add__count">{{ selected.length ? `已选择 ${selected.length} 人` : '' }}</span>
        <el-button
          v-if="canReassignOwner"
          text
          :disabled="!canMakeOwner"
          @click="makeOwner"
        >
          设为负责人
        </el-button>
      </span>
      <span class="member-add__buttons">
        <el-button
          :disabled="submitting"
          @click="close()"
        >
          取消
        </el-button>
        <el-button
          type="primary"
          :disabled="!canSubmit"
          :loading="submitting"
          @click="submit"
        >
          添加
        </el-button>
      </span>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.project-member-add-overlay) { background: var(--yp-overlay); }
:global(.project-member-add-dialog.el-dialog) { padding: var(--yp-space-6); border-radius: var(--yp-radius-xl); background: var(--yp-bg-raised); box-shadow: var(--yp-shadow-overlay); }
:global(.project-member-add-dialog .el-dialog__header) { padding: 0 0 var(--yp-space-4); }
:global(.project-member-add-dialog .el-dialog__footer) { display: flex; align-items: center; justify-content: space-between; gap: var(--yp-space-3); padding-top: var(--yp-space-5); }

.member-add__title {
  margin: 0;
  color: var(--yp-text-primary);
  font: 600 var(--yp-type-section-title-size) / var(--yp-type-section-title-line) var(--yp-font-heading);
}

.member-add__intro {
  margin: var(--yp-space-1) 0 0;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
}

.member-add {
  display: grid;
  gap: var(--yp-space-3);
}

.member-add__selected {
  display: flex;
  flex-wrap: wrap;
  gap: var(--yp-space-2);
}

.member-add__chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  max-width: 100%;
  padding: 2px var(--yp-space-1) 2px 2px;
  border-radius: var(--yp-radius-pill);
  background: var(--yp-bg-selected);
}

.member-add__chip-remove {
  display: inline-grid;
  width: 20px;
  height: 20px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--yp-text-secondary);
  background: transparent;
  cursor: pointer;
}

.member-add__chip-remove:hover:not(:disabled) {
  color: var(--yp-text-primary);
  background: var(--yp-bg-hover);
}

.member-add__results {
  display: grid;
  align-content: start;
  min-height: 220px;
  max-height: 300px;
  overflow-y: auto;
  padding: var(--yp-space-1);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
}

.member-add__option {
  display: flex;
  align-items: center;
  gap: var(--yp-space-3);
  min-height: 44px;
  padding: 0 var(--yp-space-3);
  border: 0;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-primary);
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.member-add__option:hover:not(:disabled) {
  background: var(--yp-bg-hover);
}

.member-add__option.is-selected {
  background: var(--yp-bg-selected);
}

.member-add__option:disabled {
  cursor: not-allowed;
}

.member-add__option:disabled :deep(.yp-assignee) {
  opacity: 0.55;
}

.member-add__option:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: -2px;
}

.member-add__state {
  margin-left: auto;
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  white-space: nowrap;
}

.member-add__check {
  margin-left: auto;
  color: var(--yp-action-primary);
  font-size: 16px;
}

.member-add__state + .member-add__check {
  margin-left: var(--yp-space-2);
}

.member-add__hint {
  margin: var(--yp-space-8) var(--yp-space-4);
  color: var(--yp-text-muted);
  font-size: var(--yp-type-body-size);
  text-align: center;
}

.member-add__reason {
  display: grid;
  gap: var(--yp-space-2);
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
}

.member-add__footer-start {
  display: inline-flex;
  align-items: center;
  gap: var(--yp-space-3);
}

.member-add__count {
  color: var(--yp-text-muted);
  font-size: var(--yp-type-body-size);
}

.member-add__buttons {
  display: inline-flex;
  gap: var(--yp-space-2);
}
</style>
