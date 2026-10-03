<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElButton, ElInput, ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { ProjectLifecycle, type ConnectCandidate, type ConnectColumn, type WorkItemConnection } from '@yumpoo/api-client'
import { problemMessage, type ApiProblem } from '../../../api/problems'
import InlineProblem from '../../InlineProblem.vue'
import { workItemLabelColorValue } from '../workItemLabelColors'
import type { ConnectSourceItem } from './connectColumnKeys'
import { toConnectProblem } from './connectProblems'
import { useConnectContext } from './useConnectColumns'
import ConnectionList from './ConnectionList.vue'

const props = defineProps<{ item: ConnectSourceItem; column: ConnectColumn; connections: WorkItemConnection[]; readOnly: boolean }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection]; requestCreate: [targetProjectId: string, title: string]; changed: []; close: [] }>()
const context = useConnectContext()
const targetId = ref(props.column.targets[0]?.projectId ?? ''), query = ref('')
const input = ref<InstanceType<typeof ElInput>>(), root = ref<HTMLElement>()
const candidates = ref<ConnectCandidate[]>([]), loading = ref(false), busy = ref(false), page = ref(0), totalPages = ref(0)
const problem = ref<ApiProblem>()
let timer: ReturnType<typeof setTimeout> | undefined, controller: AbortController | undefined, revision = 0
const target = computed(() => props.column.targets.find(target => target.projectId === targetId.value))
const writable = computed(() => !props.readOnly && target.value?.lifecycle === ProjectLifecycle.Active)
const canSearch = computed(() => writable.value && target.value?.actorCanLinkExisting && query.value.trim().length > 0 && query.value.trim().length <= 80)
const connected = (candidate: ConnectCandidate) => candidate.alreadyConnected || props.connections.some(connection => connection.target.workItemId === candidate.card.workItemId)
function cancelSearch() { revision++; controller?.abort(); clearTimeout(timer); loading.value = false }
async function search(append = false) {
  if (!canSearch.value || (append && (loading.value || page.value + 1 >= totalPages.value))) return
  controller?.abort(); controller = new AbortController()
  const current = ++revision, requestedPage = append ? page.value + 1 : 0
  loading.value = true; problem.value = undefined
  try {
    const result = await context.searchCandidates(props.column.id, targetId.value, props.item.id, query.value.trim(), requestedPage, controller.signal)
    if (current !== revision) return
    candidates.value = append ? [...new Map([...candidates.value, ...result.items].map(candidate => [candidate.card.workItemId, candidate])).values()] : result.items
    page.value = result.page; totalPages.value = result.totalPages
  } catch (reason) { if (current === revision) problem.value = await toConnectProblem(reason) }
  finally { if (current === revision) loading.value = false }
}
watch(() => props.column.targets, targets => { if (!targets.some(target => target.projectId === targetId.value)) targetId.value = targets[0]?.projectId ?? '' })
watch([query, targetId, writable, () => target.value?.actorCanLinkExisting, () => props.item.id], () => {
  cancelSearch(); candidates.value = []; page.value = 0; totalPages.value = 0; problem.value = undefined
  if (canSearch.value) timer = setTimeout(() => { void search() }, 300)
})
onBeforeUnmount(cancelSearch)
onMounted(() => { void nextTick(() => writable.value ? input.value?.focus() : (root.value?.querySelector<HTMLElement>('button') ?? root.value)?.focus()) })
async function link(candidate: ConnectCandidate) {
  if (busy.value || !canSearch.value || connected(candidate) || candidate.card.archived) return
  busy.value = true
  try { await context.link(props.item.id, props.column.id, candidate.card.workItemId); candidate.alreadyConnected = true; emit('changed'); ElMessage.success('已关联工作项') }
  catch (reason) { ElMessage.error(problemMessage(await toConnectProblem(reason))) } finally { busy.value = false }
}
function create() { if (writable.value && target.value) emit('requestCreate', target.value.projectId, query.value.trim() || props.item.title) }
</script>

<template>
  <section
    ref="root"
    class="connect-cell-popover"
    tabindex="-1"
    :aria-label="`${column.name}连接列表`"
    @keydown.esc.stop.prevent="emit('close')"
  >
    <header><strong>{{ column.name }}</strong><span v-if="column.targets.length === 1">→ {{ target?.name }}<span v-if="target?.lifecycle === ProjectLifecycle.Archived"> · 已归档</span></span></header>
    <div
      v-if="column.targets.length > 1"
      class="connect-cell-popover__targets"
      role="tablist"
      aria-label="目标项目"
    >
      <button
        v-for="option in column.targets"
        :key="option.projectId"
        type="button"
        role="tab"
        :aria-selected="targetId === option.projectId"
        @click="targetId = option.projectId"
      >
        {{ option.name }}<span v-if="option.lifecycle === ProjectLifecycle.Archived"> · 已归档</span>
      </button>
    </div>
    <p class="connect-cell-popover__caption">
      已连接
    </p>
    <div class="connect-cell-popover__connections">
      <connection-list
        :connections="connections"
        perspective="source"
        :read-only="readOnly"
        @open-card="emit('openCard', $event)"
        @changed="emit('changed')"
      />
    </div>
    <template v-if="writable">
      <div class="connect-cell-popover__search">
        <el-input
          ref="input"
          v-model="query"
          :prefix-icon="Search"
          maxlength="80"
          placeholder="搜索工作项，或输入标题新建"
          aria-label="搜索工作项，或输入标题新建"
          clearable
        />
        <p
          v-if="!target?.actorCanLinkExisting"
          class="connect-cell-popover__notice"
        >
          你不是「{{ target?.name }}」的成员，只能新建并关联。
        </p>
        <template v-else>
          <inline-problem
            v-if="problem"
            :problem="problem"
          />
          <div
            v-if="canSearch"
            class="connect-cell-popover__candidates"
            :aria-busy="loading"
          >
            <button
              v-for="candidate in candidates"
              :key="candidate.card.workItemId"
              type="button"
              class="connect-candidate"
              :disabled="busy || connected(candidate) || candidate.card.archived"
              @click="link(candidate)"
            >
              <span
                class="connect-candidate__dot"
                :style="{ background: workItemLabelColorValue(candidate.card.status.colorToken) }"
              />
              <span class="connect-candidate__text"><strong>{{ candidate.card.title }}</strong><small>{{ candidate.card.itemNo }}<span v-if="candidate.parent"> · 子项 · {{ candidate.parent.title }}</span></small></span>
              <small v-if="connected(candidate)">已连接</small><small v-else-if="candidate.card.archived">已归档</small>
            </button>
            <p
              v-if="loading"
              class="connect-cell-popover__caption"
              role="status"
            >
              正在搜索…
            </p>
            <p
              v-else-if="!candidates.length && !problem"
              class="connect-cell-popover__caption"
              role="status"
            >
              没有匹配的工作项，可以新建并关联。
            </p>
            <el-button
              v-if="page + 1 < totalPages"
              text
              :loading="loading"
              @click="search(true)"
            >
              加载更多
            </el-button>
            <el-button
              v-if="problem"
              text
              @click="search()"
            >
              重试搜索
            </el-button>
          </div>
        </template>
      </div>
      <button
        type="button"
        class="connect-cell-popover__create"
        :disabled="busy"
        @click="create"
      >
        ＋ 在「{{ target?.name }}」中新建并关联
      </button>
    </template>
  </section>
</template>

<style scoped>
.connect-cell-popover { display: grid; gap: 8px; outline: none; }
.connect-cell-popover header { display: flex; min-width: 0; justify-content: space-between; align-items: baseline; gap: 12px; font-size: 13px; }
.connect-cell-popover header strong { color: var(--yp-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.connect-cell-popover header > span { color: var(--yp-text-muted); font-size: 11px; }
.connect-cell-popover__targets { display: flex; gap: 4px; overflow-x: auto; }
.connect-cell-popover__targets button { flex-shrink: 0; padding: 5px 8px; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-secondary); font-size: 12px; cursor: pointer; }
.connect-cell-popover__targets button[aria-selected="true"] { border-color: var(--yp-action-primary); background: var(--yp-bg-selected); color: var(--yp-action-primary); }
.connect-cell-popover__caption { margin: 0; color: var(--yp-text-muted); font-size: 12px; }
.connect-cell-popover__connections, .connect-cell-popover__candidates { max-height: 240px; overflow-y: auto; }
.connect-cell-popover__search { display: grid; gap: 8px; padding-top: 12px; border-top: 1px solid var(--yp-border-subtle); }
.connect-cell-popover__notice { margin: 0; padding: 10px; border-radius: var(--yp-radius-sm); background: var(--yp-bg-sunken); color: var(--yp-text-secondary); font-size: 12px; line-height: 1.7; }
.connect-cell-popover__create { min-height: 38px; padding: 8px; border: 0; border-top: 1px solid var(--yp-border-subtle); background: transparent; color: var(--yp-action-primary); text-align: left; font-size: 13px; cursor: pointer; }
.connect-cell-popover__create:hover { background: var(--yp-bg-hover); }
.connect-candidate { display: flex; width: 100%; min-height: 44px; align-items: center; gap: 8px; padding: 6px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-primary); text-align: left; cursor: pointer; }
.connect-candidate:hover:not(:disabled) { background: var(--yp-bg-hover); }
.connect-candidate:disabled { color: var(--yp-text-muted); cursor: default; }
.connect-candidate__dot { width: 8px; height: 8px; flex-shrink: 0; border-radius: var(--yp-radius-pill); }
.connect-candidate__text { display: grid; flex: 1; min-width: 0; gap: 3px; }
.connect-candidate__text strong { font-size: 13px; font-weight: 400; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.connect-candidate small { font-size: 11px; color: var(--yp-text-muted); }
</style>
