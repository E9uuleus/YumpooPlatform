<script setup lang="ts">
import {
  WorkItemRelationCandidateEligibilityEnum,
  WorkItemRelationRole,
  WorkItemRelationType,
  readCsrfToken,
  type WorkItemRelation,
  type WorkItemRelationCandidate,
} from '@yumpoo/api-client'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { workItemsApi } from '../../api/client'
import { isProblemStatus, problemMessage, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../InlineProblem.vue'

const props = defineProps<{ workItemId: string }>()
const emit = defineEmits<{
  changed: [affectedWorkItemIds: string[]]
  openWorkItem: [workItemId: string]
}>()

const relations = ref<WorkItemRelation[]>([])
const relationPage = ref(0)
const relationPages = ref(0)
const canCreate = ref(false)
const loading = ref(false)
const problem = ref<ApiProblem>()
const dialogVisible = ref(false)
const relationType = ref(WorkItemRelationType.Related)
const currentRole = ref(WorkItemRelationRole.Related)
const query = ref('')
const candidates = ref<WorkItemRelationCandidate[]>([])
const candidatePage = ref(0)
const candidatePages = ref(0)
const searching = ref(false)
const mutating = ref(false)
const mutationSignature = ref('')
const mutationKey = ref('')

const typeOptions = [
  { value: WorkItemRelationType.ParentChild, label: '父子' },
  { value: WorkItemRelationType.Related, label: '相关' },
  { value: WorkItemRelationType.Blocks, label: '阻塞' },
  { value: WorkItemRelationType.Source, label: '来源' },
  { value: WorkItemRelationType.Duplicate, label: '重复' },
]
const rolesByType: Record<WorkItemRelationType, { value: WorkItemRelationRole, label: string }[]> = {
  [WorkItemRelationType.ParentChild]: [
    { value: WorkItemRelationRole.Parent, label: '当前事项是父项' },
    { value: WorkItemRelationRole.Child, label: '当前事项是子项' },
  ],
  [WorkItemRelationType.Related]: [{ value: WorkItemRelationRole.Related, label: '相互相关' }],
  [WorkItemRelationType.Blocks]: [
    { value: WorkItemRelationRole.Blocks, label: '当前事项阻塞对方' },
    { value: WorkItemRelationRole.BlockedBy, label: '当前事项被对方阻塞' },
  ],
  [WorkItemRelationType.Source]: [
    { value: WorkItemRelationRole.Source, label: '当前事项是来源' },
    { value: WorkItemRelationRole.DerivedFrom, label: '当前事项源自对方' },
  ],
  [WorkItemRelationType.Duplicate]: [
    { value: WorkItemRelationRole.DuplicateOf, label: '当前事项重复于对方' },
    { value: WorkItemRelationRole.Canonical, label: '当前事项是规范项' },
  ],
  [WorkItemRelationType.UnknownDefaultOpenApi]: [],
}
const roleLabels: Record<WorkItemRelationRole, string> = {
  [WorkItemRelationRole.Parent]: '子项',
  [WorkItemRelationRole.Child]: '父项',
  [WorkItemRelationRole.Related]: '相关事项',
  [WorkItemRelationRole.Blocks]: '被当前事项阻塞',
  [WorkItemRelationRole.BlockedBy]: '阻塞当前事项',
  [WorkItemRelationRole.Source]: '衍生事项',
  [WorkItemRelationRole.DerivedFrom]: '来源事项',
  [WorkItemRelationRole.DuplicateOf]: '规范事项',
  [WorkItemRelationRole.Canonical]: '重复事项',
  [WorkItemRelationRole.UnknownDefaultOpenApi]: '其他关系',
}
const reasonLabels: Record<string, string> = {
  ALREADY_RELATED: '关系已存在',
  CHILD_ALREADY_HAS_PARENT: '已有父项，需要显式换父',
  PARENT_IS_CHILD: '子项不能成为父项',
  CHILD_HAS_CHILDREN: '有子项的根项不能成为子项',
}

const groups = computed(() => {
  const grouped = new Map<WorkItemRelationRole, WorkItemRelation[]>()
  for (const relation of relations.value) {
    const list = grouped.get(relation.currentRole) ?? []
    list.push(relation)
    grouped.set(relation.currentRole, list)
  }
  return [...grouped.entries()].map(([role, items]) => ({ role, label: roleLabels[role], items }))
})

watch(relationType, type => {
  currentRole.value = rolesByType[type][0]?.value ?? WorkItemRelationRole.Related
  candidates.value = []
  candidatePages.value = 0
})

watch(() => props.workItemId, () => {
  relationPage.value = 0
  void loadRelations()
})

onMounted(loadRelations)

async function loadRelations(page = relationPage.value): Promise<void> {
  loading.value = true
  problem.value = undefined
  try {
    const result = await workItemsApi.listWorkItemRelations({
      workItemId: props.workItemId,
      page,
      size: 20,
    })
    relations.value = result.items
    relationPage.value = result.page
    relationPages.value = result.totalPages
    canCreate.value = result.canCreate
  } catch (reason) {
    problem.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  dialogVisible.value = true
  query.value = ''
  candidates.value = []
  problem.value = undefined
  mutationSignature.value = ''
  mutationKey.value = ''
}

async function search(page = 0): Promise<void> {
  const text = query.value.trim()
  if (!text) return
  searching.value = true
  problem.value = undefined
  try {
    const result = await workItemsApi.listWorkItemRelationCandidates({
      workItemId: props.workItemId,
      relationType: relationType.value,
      currentRole: currentRole.value,
      q: text,
      page,
      size: 12,
    })
    candidates.value = result.items
    candidatePage.value = result.page
    candidatePages.value = result.totalPages
  } catch (reason) {
    problem.value = await toApiProblem(reason)
  } finally {
    searching.value = false
  }
}

function keyFor(signature: string): string {
  if (mutationSignature.value !== signature || !mutationKey.value) {
    mutationSignature.value = signature
    mutationKey.value = globalThis.crypto.randomUUID()
  }
  return mutationKey.value
}

async function choose(candidate: WorkItemRelationCandidate): Promise<void> {
  if (candidate.eligibility === WorkItemRelationCandidateEligibilityEnum.Ineligible) return
  if (candidate.eligibility === WorkItemRelationCandidateEligibilityEnum.ReparentRequired) {
    await reparent(candidate)
    return
  }
  const csrf = readCsrfToken()
  if (!csrf) {
    problem.value = await toApiProblem(new Error('CSRF token missing'))
    return
  }
  const signature = `create:${relationType.value}:${currentRole.value}:${candidate.item.id}`
  mutating.value = true
  problem.value = undefined
  try {
    const response = await workItemsApi.createWorkItemRelationRaw({
      workItemId: props.workItemId,
      xXSRFTOKEN: csrf,
      idempotencyKey: keyFor(signature),
      workItemRelationCreateRequest: {
        relationType: relationType.value,
        currentRole: currentRole.value,
        targetWorkItemId: candidate.item.id,
      },
    })
    await response.value()
    ElMessage.success(response.raw.status === 200 ? '关系已存在' : '关系已创建')
    mutationKey.value = ''
    dialogVisible.value = false
    await changed([props.workItemId, candidate.item.id])
  } catch (reason) {
    await mutationFailed(reason)
  } finally {
    mutating.value = false
  }
}

async function reparent(candidate: WorkItemRelationCandidate): Promise<void> {
  const active = candidate.activeParent
  if (!active) return
  try {
    await ElMessageBox.confirm(
      `该事项当前父项为 ${active.parent.itemNo} ${active.parent.title}，是否原子更换父项？`,
      '确认更换父项',
      { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' },
    )
    const { value } = await ElMessageBox.prompt('请填写换父原因', '更换父项', {
      inputValidator: text => Boolean(text?.trim()) || '原因不能为空',
      inputPlaceholder: '请输入原因',
    })
    const csrf = readCsrfToken()
    if (!csrf) throw new Error('CSRF token missing')
    const newParentWorkItemId = currentRole.value === WorkItemRelationRole.Parent
      ? props.workItemId
      : candidate.item.id
    const signature = `reparent:${active.relationId}:${newParentWorkItemId}:${value.trim()}`
    mutating.value = true
    await workItemsApi.changeWorkItemParent({
      relationId: active.relationId,
      xXSRFTOKEN: csrf,
      ifMatch: active.etag,
      idempotencyKey: keyFor(signature),
      workItemParentChangeRequest: { newParentWorkItemId, reason: value.trim() },
    })
    ElMessage.success('父项已更换')
    mutationKey.value = ''
    dialogVisible.value = false
    await changed([props.workItemId, candidate.item.id, active.parent.id])
  } catch (reason) {
    if (!cancelled(reason)) await mutationFailed(reason)
  } finally {
    mutating.value = false
  }
}

async function remove(relation: WorkItemRelation): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('解除后不会恢复原关系；重新关联会创建新关系。', '解除关系', {
      inputValidator: text => Boolean(text?.trim()) || '原因不能为空',
      inputPlaceholder: '请输入解除原因',
      confirmButtonText: '解除',
      cancelButtonText: '取消',
    })
    const csrf = readCsrfToken()
    if (!csrf) throw new Error('CSRF token missing')
    const signature = `delete:${relation.id}:${value.trim()}`
    mutating.value = true
    await workItemsApi.deleteWorkItemRelation({
      relationId: relation.id,
      xXSRFTOKEN: csrf,
      ifMatch: relation.etag,
      idempotencyKey: keyFor(signature),
      workItemRelationDeleteRequest: { reason: value.trim() },
    })
    ElMessage.success('关系已解除')
    mutationKey.value = ''
    await changed([props.workItemId, relation.counterpart.id])
  } catch (reason) {
    if (!cancelled(reason)) await mutationFailed(reason)
  } finally {
    mutating.value = false
  }
}

async function mutationFailed(reason: unknown): Promise<void> {
  problem.value = await toApiProblem(reason)
  if (isProblemStatus(problem.value, 409) || isProblemStatus(problem.value, 412)) {
    await loadRelations()
    if (dialogVisible.value && query.value.trim()) await search(candidatePage.value)
    ElMessage.warning('关系事实已刷新，请确认后重试')
  } else {
    ElMessage.error(problemMessage(problem.value))
  }
}

async function changed(ids: string[]): Promise<void> {
  await loadRelations(0)
  emit('changed', [...new Set(ids)])
}

function cancelled(reason: unknown): boolean {
  return reason === 'cancel' || reason === 'close'
}
</script>

<template>
  <section v-loading="loading" class="relations" aria-label="事项关系">
    <div class="relations__toolbar">
      <span>共 {{ relations.length }} 条当前页关系</span>
      <el-button v-if="canCreate" type="primary" @click="openCreate">添加关系</el-button>
    </div>
    <inline-problem v-if="problem && !dialogVisible" :problem="problem" />
    <el-empty v-if="!loading && !relations.length" description="暂无关系" />
    <section v-for="group in groups" :key="group.role" class="relation-group">
      <h3>{{ group.label }}</h3>
      <ul class="relation-list">
        <li v-for="relation in group.items" :key="relation.id">
          <button
            type="button"
            class="relation-link"
            :disabled="relation.counterpart.deleted"
            @click="emit('openWorkItem', relation.counterpart.id)"
          >
            <strong>{{ relation.counterpart.itemNo }}</strong>
            <span>{{ relation.counterpart.title }}</span>
          </button>
          <el-tag v-if="relation.counterpart.deleted" type="info">已删除</el-tag>
          <el-button
            v-if="relation.capabilities.canDelete"
            link
            type="danger"
            :disabled="mutating"
            @click="remove(relation)"
          >解除</el-button>
        </li>
      </ul>
    </section>
    <div v-if="relationPages > 1" class="relations__pager">
      <el-button :disabled="relationPage === 0" @click="loadRelations(relationPage - 1)">上一页</el-button>
      <span>{{ relationPage + 1 }} / {{ relationPages }}</span>
      <el-button :disabled="relationPage + 1 >= relationPages" @click="loadRelations(relationPage + 1)">下一页</el-button>
    </div>

    <el-dialog v-model="dialogVisible" title="添加事项关系" width="min(680px, 92vw)">
      <el-form label-position="top">
        <div class="relations__selectors">
          <el-form-item label="关系类型">
            <el-select v-model="relationType">
              <el-option v-for="option in typeOptions" :key="option.value" v-bind="option" />
            </el-select>
          </el-form-item>
          <el-form-item label="当前侧语义">
            <el-select v-model="currentRole">
              <el-option v-for="option in rolesByType[relationType]" :key="option.value" v-bind="option" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="搜索同项目事项">
          <el-input v-model="query" maxlength="80" clearable @keyup.enter="search(0)">
            <template #append><el-button :loading="searching" @click="search(0)">搜索</el-button></template>
          </el-input>
        </el-form-item>
      </el-form>
      <inline-problem v-if="problem" :problem="problem" />
      <ul class="candidate-list" aria-live="polite">
        <li v-for="candidate in candidates" :key="candidate.item.id">
          <button
            type="button"
            :disabled="candidate.eligibility === WorkItemRelationCandidateEligibilityEnum.Ineligible || mutating"
            @click="choose(candidate)"
          >
            <strong>{{ candidate.item.itemNo }}</strong>
            <span>{{ candidate.item.title }}</span>
            <small v-if="candidate.reasonCode">{{ reasonLabels[candidate.reasonCode] ?? candidate.reasonCode }}</small>
            <small v-if="candidate.activeParent">当前父项：{{ candidate.activeParent.parent.itemNo }} {{ candidate.activeParent.parent.title }}</small>
          </button>
        </li>
      </ul>
      <div v-if="candidatePages > 1" class="relations__pager">
        <el-button :disabled="candidatePage === 0" @click="search(candidatePage - 1)">上一页</el-button>
        <span>{{ candidatePage + 1 }} / {{ candidatePages }}</span>
        <el-button :disabled="candidatePage + 1 >= candidatePages" @click="search(candidatePage + 1)">下一页</el-button>
      </div>
    </el-dialog>
  </section>
</template>

<style scoped>
.relations { min-height: 180px; padding: var(--yp-space-3) 0; }
.relations__toolbar, .relations__pager { display: flex; align-items: center; justify-content: space-between; gap: var(--yp-space-3); }
.relation-group h3 { margin: var(--yp-space-4) 0 var(--yp-space-2); font-size: 0.9rem; }
.relation-list, .candidate-list { display: grid; gap: var(--yp-space-2); margin: 0; padding: 0; list-style: none; }
.relation-list li { display: flex; align-items: center; gap: var(--yp-space-2); padding: var(--yp-space-2); border: 1px solid var(--el-border-color-lighter); border-radius: var(--yp-radius-md); }
.relation-link { display: flex; flex: 1; gap: var(--yp-space-2); min-width: 0; padding: 0; border: 0; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.relation-link:disabled { cursor: default; opacity: 0.65; }
.relations__selectors { display: grid; grid-template-columns: 1fr 1fr; gap: var(--yp-space-3); }
.candidate-list button { display: grid; width: 100%; gap: 3px; padding: var(--yp-space-3); border: 1px solid var(--el-border-color); border-radius: var(--yp-radius-md); background: var(--el-bg-color); text-align: left; cursor: pointer; }
.candidate-list button:hover:not(:disabled), .candidate-list button:focus-visible { border-color: var(--el-color-primary); }
.candidate-list button:disabled { cursor: not-allowed; opacity: 0.55; }
.candidate-list small { color: var(--el-text-color-secondary); }
.relations__pager { justify-content: center; margin-top: var(--yp-space-3); }
@media (max-width: 600px) { .relations__selectors { grid-template-columns: 1fr; } }
</style>
