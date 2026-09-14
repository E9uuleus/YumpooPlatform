<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { ElInput, ElMessage } from 'element-plus'
import { readCsrfToken, WorkItemRelationType, WorkItemRelationRole, WorkItemRelationCandidateEligibilityEnum,
  type ProjectWorkItemListItem, type WorkItemRelationCandidate } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../InlineProblem.vue'

const props = defineProps<{ item: ProjectWorkItemListItem }>()
const emit = defineEmits<{ close: []; changed: [affectedIds: string[]]; busyChange: [busy: boolean] }>()
const query = ref('')
const searchInput = ref<InstanceType<typeof ElInput>>()
const candidates = ref<WorkItemRelationCandidate[]>([])
const page = ref(0)
const pages = ref(0)
const loading = ref(false)
const loadingMore = ref(false)
const retryPage = ref(0)
const saving = ref(false)
const problem = ref<ApiProblem>()
let revision = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined
const reasons: Record<string, string> = { PARENT_IS_CHILD: '该工作项已是子项', CHILD_HAS_CHILDREN: '当前工作项已有子项',
  CHILD_ALREADY_HAS_PARENT: '当前工作项已有父项', ALREADY_RELATED: '关系已存在' }

async function search(nextPage = 0): Promise<void> {
  if (nextPage > 0 && (loading.value || loadingMore.value || saving.value)) return
  const current = ++revision
  loading.value = nextPage === 0
  loadingMore.value = nextPage > 0
  retryPage.value = nextPage
  problem.value = undefined
  try {
    const result = await workItemsApi.listWorkItemRelationCandidates({ workItemId: props.item.id,
      targetProjectId: props.item.projectId, relationType: WorkItemRelationType.ParentChild,
      currentRole: WorkItemRelationRole.Child,
      q: query.value.trim() || props.item.itemNo.slice(0, props.item.itemNo.lastIndexOf('-')),
      page: nextPage, size: 12 })
    if (current !== revision) return
    candidates.value = nextPage === 0 ? result.items
      : [...new Map([...candidates.value, ...result.items].map(candidate => [candidate.item.id, candidate])).values()]
    page.value = result.page
    pages.value = result.totalPages
  } catch (reason) { if (current === revision) problem.value = await toApiProblem(reason) }
  finally { if (current === revision) { loading.value = false; loadingMore.value = false } }
}

function loadMore(event: Event): void {
  const list = event.currentTarget
  if (!(list instanceof HTMLElement) || problem.value || page.value + 1 >= pages.value) return
  if (list.scrollHeight - list.scrollTop - list.clientHeight <= 72) void search(page.value + 1)
}

async function choose(candidate: WorkItemRelationCandidate): Promise<void> {
  if (saving.value || loading.value || candidate.eligibility !== WorkItemRelationCandidateEligibilityEnum.Eligible) return
  const token = readCsrfToken()
  if (!token) { problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  saving.value = true
  problem.value = undefined
  try {
    await workItemsApi.createWorkItemRelation({ workItemId: props.item.id, xXSRFTOKEN: token,
      idempotencyKey: crypto.randomUUID(), workItemRelationCreateRequest: {
        relationType: WorkItemRelationType.ParentChild, currentRole: WorkItemRelationRole.Child,
        targetProjectId: props.item.projectId, targetWorkItemId: candidate.item.id,
      } })
    emit('changed', [props.item.id, candidate.item.id])
    ElMessage.success(`已转为“${candidate.item.title}”的子工作项`)
    emit('close')
  } catch (reason) { problem.value = await toApiProblem(reason) }
  finally { saving.value = false }
}

watch(query, () => {
  revision += 1
  candidates.value = []
  loading.value = true
  loadingMore.value = false
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void search(), 250)
})
watch(saving, value => emit('busyChange', value), { flush: 'sync' })
onMounted(() => void search())
onBeforeUnmount(() => { revision += 1; clearTimeout(searchTimer) })
defineExpose({ focusSearch: () => searchInput.value?.focus() })
</script>

<template>
  <section
    class="parent-picker"
    role="dialog"
    aria-label="选择父工作项"
    @pointermove.stop
    @pointerdown.stop
    @click.stop
    @keydown.stop
    @keydown.esc.stop.prevent="!saving && emit('close')"
  >
    <h3>选择父工作项</h3>
    <el-input
      ref="searchInput"
      v-model="query"
      clearable
      placeholder="搜索工作项名称"
      :disabled="saving"
      aria-label="搜索父工作项"
    />
    <inline-problem
      v-if="problem"
      :problem="problem"
      @retry="search(retryPage)"
    />
    <div
      class="parent-picker-results"
      :aria-busy="loading || loadingMore"
      @scroll="loadMore"
    >
      <p v-if="loading">
        正在加载工作项…
      </p>
      <p v-else-if="!candidates.length">
        没有匹配的工作项
      </p>
      <button
        v-for="candidate in candidates"
        :key="candidate.item.id"
        type="button"
        class="parent-picker-option"
        :title="candidate.reasonCode ? reasons[candidate.reasonCode] ?? '该工作项不可选择' : candidate.item.title"
        :disabled="saving || loading || candidate.eligibility !== WorkItemRelationCandidateEligibilityEnum.Eligible"
        @click="choose(candidate)"
      >
        <span>{{ candidate.item.title }}</span>
      </button>
      <p
        v-if="loadingMore"
        class="parent-picker-loading"
      >
        正在加载…
      </p>
    </div>
  </section>
</template>

<style scoped>
.parent-picker h3 { margin: 0 0 10px; font-size: 16px; font-weight: 600; color: var(--yp-text-primary); }
.parent-picker-results { display: grid; align-content: start; gap: 4px; margin-top: 12px; height: min(360px, calc(100dvh - 150px)); overflow-y: auto; overscroll-behavior: contain; }
.parent-picker-option { position: relative; display: flex; align-items: center; width: 100%; height: var(--work-item-table-row-height, 36px); box-sizing: border-box; padding: 0 12px 0 18px; border: 1px solid var(--yp-monday-grid-border, var(--yp-border-subtle)); border-left: 0; border-radius: var(--work-item-hierarchy-corner-radius, 6px) 0 0 var(--work-item-hierarchy-corner-radius, 6px); background: var(--yp-bg-surface); text-align: left; font: inherit; font-size: 13px; color: var(--yp-text-primary); cursor: pointer; }
.parent-picker-option::before { position: absolute; top: -1px; bottom: -1px; left: 0; width: var(--work-item-hierarchy-bar-width, 6px); border-radius: var(--work-item-hierarchy-corner-radius, 6px) 0 0 var(--work-item-hierarchy-corner-radius, 6px); background: var(--work-item-group-accent, rgb(87, 155, 252)); content: ''; }
.parent-picker-option span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.parent-picker-option:hover:not(:disabled) { background: var(--yp-bg-sunken); }
.parent-picker-option:focus-visible { background: var(--yp-bg-selected); outline: 2px solid var(--yp-action-primary); outline-offset: -2px; }
.parent-picker-option:disabled { opacity: .5; cursor: not-allowed; }
.parent-picker-loading { margin: 6px 0; color: var(--yp-text-secondary); text-align: center; }
</style>
