<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { ElCheckbox, ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon, ElInput, ElMessage, ElPopover, ElTooltip } from 'element-plus'
import { ArrowDown, Search, Setting, TopRight } from '@element-plus/icons-vue'
import { ConnectCandidateSort, ProjectLifecycle, type ConnectCandidate, type ConnectCandidateField, type ConnectColumn,
  type ConnectColumnIncoming, type ConnectCreateOptions, type ConnectionCard, type WorkItemConnection } from '@yumpoo/api-client'
import { problemMessage, type ApiProblem } from '../../../api/problems'
import InlineProblem from '../../InlineProblem.vue'
import { workItemLabelColorValue } from '../workItemLabelColors'
import '../workItemAccentBar.css'
import type { ConnectSourceItem } from './connectColumnKeys'
import { toConnectProblem } from './connectProblems'
import { defaultConnectSearchFields, isDefaultConnectSearch } from './connectSearchFields'
import { connectTableActions } from './connectTableActions'
import { useConnectContext } from './useConnectColumns'
import ConnectSearchFieldsMenu from './ConnectSearchFieldsMenu.vue'

const UNDO_MS = 6000
/** A forward column picks items in its target projects; a reverse column picks items in the column's own project. */
const props = defineProps<{ item: ConnectSourceItem; column?: ConnectColumn | undefined; reverse?: ConnectColumnIncoming | undefined
  connections: WorkItemConnection[]; total: number; readOnly: boolean }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection]; changed: []; close: [] }>()
const context = useConnectContext(), actions = inject(connectTableActions, undefined)
const forward = computed(() => Boolean(props.column))
const targetId = ref(props.column?.targets[0]?.projectId ?? '')
const project = computed(() => {
  if (props.column) {
    const target = props.column.targets.find(value => value.projectId === targetId.value)
    return target && { id: target.projectId, name: target.name, lifecycle: target.lifecycle, canLink: target.actorCanLinkExisting }
  }
  return props.reverse && { id: props.reverse.projectId, name: props.reverse.projectName, lifecycle: props.reverse.projectLifecycle,
    canLink: props.reverse.actorCanLinkExisting }
})
const writable = computed(() => !props.readOnly && project.value?.lifecycle === ProjectLifecycle.Active)
const canSearch = computed(() => writable.value && Boolean(project.value?.canLink))
// Creating from a forward column is an intake the target need not approve; from a reverse column it needs source membership.
const canCreate = computed(() => writable.value && (forward.value || Boolean(project.value?.canLink)))
const canSettings = computed(() => forward.value && Boolean(actions?.canManage.value))
const notice = computed(() => {
  if (!writable.value || canSearch.value) return ''
  return forward.value ? `你不是「${project.value?.name}」的成员，只能新建并关联。` : `你不是「${project.value?.name}」的成员，只能查看和解除这里的连接。`
})

const query = ref(''), fields = ref<ConnectCandidateField[]>([...defaultConnectSearchFields]), sort = ref<ConnectCandidateSort>(ConnectCandidateSort.Recent)
const fieldsOpen = ref(false)
const candidates = ref<ConnectCandidate[]>([]), loading = ref(false), page = ref(0), totalPages = ref(0), problem = ref<ApiProblem>()
const busy = ref<ReadonlySet<string>>(new Set()), creating = ref(false)
const options = shallowRef<ConnectCreateOptions>(), contentId = ref<string>()
const extra = ref<WorkItemConnection[]>([]), extraPage = ref(1), extraLoading = ref(false)
const undo = shallowRef<{ card: ConnectionCard }>()
const input = ref<InstanceType<typeof ElInput>>(), root = ref<HTMLElement>(), list = ref<HTMLElement>(), sentinel = ref<HTMLElement>()
let timer: ReturnType<typeof setTimeout> | undefined, undoTimer: ReturnType<typeof setTimeout> | undefined
let controller: AbortController | undefined, revision = 0, observer: IntersectionObserver | undefined
const observable = typeof IntersectionObserver !== 'undefined'

const card = (connection: WorkItemConnection) => forward.value ? connection.target : connection.source
const color = (value: ConnectionCard) => workItemLabelColorValue(value.status.colorToken)
const connected = computed(() => [...new Map([...props.connections, ...extra.value].filter(connection => connection.active
  && (!forward.value || card(connection).projectId === targetId.value)).map(connection => [connection.id, connection])).values()])
const connectedIds = computed(() => new Set(connected.value.map(connection => card(connection).workItemId)))
const hiddenConnections = computed(() => forward.value ? 0 : Math.max(0, props.total - connected.value.length))
const targetCounts = computed(() => new Map(props.column?.targets.map(target => [target.projectId,
  props.connections.filter(connection => connection.active && connection.target.projectId === target.projectId).length]) ?? []))
const groups = computed(() => {
  const result = new Map<string, { key: string; name: string; color: string; items: ConnectCandidate[] }>()
  for (const candidate of candidates.value) {
    if (candidate.alreadyConnected || connectedIds.value.has(candidate.card.workItemId)) continue
    const status = candidate.card.status
    if (!result.has(status.code)) result.set(status.code, { key: status.code, name: status.name, color: color(candidate.card), items: [] })
    result.get(status.code)!.items.push(candidate)
  }
  return [...result.values()]
})
const category = computed(() => options.value?.categories.find(value => value.id === contentId.value))

function cancelSearch() { revision++; controller?.abort(); clearTimeout(timer); loading.value = false }
async function load(append = false) {
  if (!canSearch.value || (append && (loading.value || page.value + 1 >= totalPages.value))) return
  controller?.abort(); controller = new AbortController()
  const current = ++revision, requested = append ? page.value + 1 : 0, text = query.value.trim()
  const search = { fields: fields.value, sort: sort.value }
  loading.value = true; problem.value = undefined
  try {
    const result = props.column
      ? await context.searchCandidates(props.column.id, targetId.value, props.item.id, text, requested, controller.signal, search)
      : await context.reverseCandidates(props.reverse!.columnId, props.item.id, text, requested, controller.signal, search)
    if (current !== revision) return
    candidates.value = append ? [...new Map([...candidates.value, ...result.items].map(value => [value.card.workItemId, value])).values()] : result.items
    page.value = result.page; totalPages.value = result.totalPages
  } catch (reason) { if (current === revision) problem.value = await toConnectProblem(reason) }
  finally { if (current === revision) loading.value = false }
}
watch(() => props.column?.targets, targets => { if (targets && !targets.some(value => value.projectId === targetId.value)) targetId.value = targets[0]?.projectId ?? '' })
watch([query, targetId, () => fields.value.join(), sort, canSearch, () => props.item.id], ([text], previous) => {
  cancelSearch(); candidates.value = []; page.value = 0; totalPages.value = 0; problem.value = undefined
  if (!canSearch.value) return
  // Typing waits for a pause; opening, switching projects, fields or order load at once.
  const typing = previous?.length ? text !== previous[0] : false
  timer = setTimeout(() => { void load() }, typing ? 300 : 0)
}, { immediate: true })
watch([() => query.value.trim().length > 0, targetId, canCreate], async ([typed]) => {
  if (!typed || !canCreate.value || options.value?.targetProjectId === (forward.value ? targetId.value : props.reverse?.projectId)) return
  try {
    const result = props.column ? await context.createOptions(props.column.id, targetId.value) : await context.reverseCreateOptions(props.reverse!.columnId, props.item.id)
    options.value = result; contentId.value = result.defaultContentId
  } catch { options.value = undefined; contentId.value = undefined }
})
watch(() => props.item.id, () => { extra.value = []; extraPage.value = 1; undo.value = undefined })
onMounted(() => {
  void nextTick(() => (canSearch.value || canCreate.value ? input.value?.focus() : root.value?.focus()))
  if (observable && list.value && sentinel.value) {
    observer = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) void load(true) }, { root: list.value, rootMargin: '48px' })
    observer.observe(sentinel.value)
  }
})
onBeforeUnmount(() => { cancelSearch(); observer?.disconnect(); clearTimeout(undoTimer) })

function setBusy(id: string, value: boolean) {
  const next = new Set(busy.value)
  if (value) next.add(id); else next.delete(id)
  busy.value = next
}
async function link(value: ConnectionCard) {
  if (!canSearch.value || busy.value.has(value.workItemId) || value.archived) return
  setBusy(value.workItemId, true)
  try {
    if (props.column) await context.link(props.item.id, props.column.id, value.workItemId)
    else await context.reverseLink(props.item.id, props.reverse!.columnId, value.workItemId)
    candidates.value = candidates.value.map(candidate => candidate.card.workItemId === value.workItemId ? { ...candidate, alreadyConnected: true } : candidate)
    emit('changed')
  } catch (reason) { ElMessage.error(problemMessage(await toConnectProblem(reason, { operation: 'link' }))) }
  finally { setBusy(value.workItemId, false) }
}
async function unlink(connection: WorkItemConnection) {
  const value = card(connection)
  if (props.readOnly || !connection.capabilities.canUnlink || busy.value.has(value.workItemId)) return
  setBusy(value.workItemId, true)
  try {
    await context.unlink(connection)
    extra.value = extra.value.filter(item => item.id !== connection.id)
    candidates.value = candidates.value.map(candidate => candidate.card.workItemId === value.workItemId ? { ...candidate, alreadyConnected: false } : candidate)
    emit('changed')
    clearTimeout(undoTimer)
    undo.value = canSearch.value ? { card: value } : undefined
    undoTimer = setTimeout(() => { undo.value = undefined }, UNDO_MS)
  } catch (reason) { ElMessage.error(problemMessage(await toConnectProblem(reason))) }
  finally { setBusy(value.workItemId, false) }
}
async function restore() {
  const value = undo.value?.card
  undo.value = undefined
  if (value) await link(value)
}
async function create() {
  const title = query.value.trim()
  if (!title || !canCreate.value || creating.value) return
  creating.value = true
  try {
    if (props.column) await context.createAndLink(props.item.id, { columnId: props.column.id, targetProjectId: targetId.value, title, contentId: contentId.value ?? null })
    else await context.reverseCreateAndLink(props.item.id, { columnId: props.reverse!.columnId, title, contentId: contentId.value ?? null })
    query.value = ''
    emit('changed')
    ElMessage.success(`已在「${project.value?.name}」新建并关联`)
  } catch (reason) { ElMessage.error(problemMessage(await toConnectProblem(reason, { operation: 'createAndLink' }))) }
  finally { creating.value = false }
}
async function loadMoreConnected() {
  if (forward.value || extraLoading.value) return
  extraLoading.value = true
  try {
    const result = await context.incoming(props.item.id, extraPage.value, undefined, props.reverse!.columnId)
    extra.value = [...extra.value, ...result.items]; extraPage.value = result.page + 1
  } catch (reason) { ElMessage.error(problemMessage(await toConnectProblem(reason))) }
  finally { extraLoading.value = false }
}
function openSettings() { if (props.column && actions) { emit('close'); actions.openSettings(props.column) } }
function toggleSort() { sort.value = sort.value === ConnectCandidateSort.Recent ? ConnectCandidateSort.Title : ConnectCandidateSort.Recent }
</script>

<template>
  <section
    ref="root"
    class="connect-picker"
    tabindex="-1"
    :aria-label="`${column?.name ?? project?.name}：选择工作项`"
    @keydown.esc.stop.prevent="emit('close')"
  >
    <header class="connect-picker__header">
      <h3>选择工作项</h3>
      <div class="connect-picker__tools">
        <el-tooltip
          v-if="canSearch"
          :content="sort === ConnectCandidateSort.Recent ? '当前按最近更新排序，点击改为按名称' : '当前按名称排序，点击改为按最近更新'"
          placement="top"
        >
          <button
            type="button"
            class="connect-picker__icon"
            :class="{ 'is-active': sort === ConnectCandidateSort.Title }"
            :aria-label="sort === ConnectCandidateSort.Recent ? '按名称排序' : '按最近更新排序'"
            @click="toggleSort"
          >
            <svg
              viewBox="0 0 16 16"
              aria-hidden="true"
            ><path d="M5 2.5v11m0 0-2.5-2.5M5 13.5 7.5 11M11 13.5v-11m0 0L8.5 5M11 2.5 13.5 5" /></svg>
          </button>
        </el-tooltip>
        <button
          v-if="canSettings"
          type="button"
          class="connect-picker__settings"
          @click="openSettings"
        >
          <el-icon><setting /></el-icon>连接设置
        </button>
      </div>
    </header>
    <div
      v-if="column && column.targets.length > 1"
      class="connect-picker__tabs"
      role="tablist"
      aria-label="目标项目"
    >
      <button
        v-for="target in column.targets"
        :key="target.projectId"
        type="button"
        role="tab"
        :aria-selected="targetId === target.projectId"
        @click="targetId = target.projectId"
      >
        {{ target.name }}<small v-if="targetCounts.get(target.projectId)">{{ targetCounts.get(target.projectId) }}</small>
      </button>
    </div>
    <p
      v-else
      class="connect-picker__project"
    >
      {{ project?.name }}<span v-if="!forward"> · 双向连接</span><span v-if="project?.lifecycle === ProjectLifecycle.Archived"> · 已归档</span>
    </p>
    <div
      v-if="canSearch || canCreate"
      class="connect-picker__search"
    >
      <el-input
        ref="input"
        v-model="query"
        :prefix-icon="Search"
        maxlength="80"
        :placeholder="canSearch ? '搜索或新建工作项' : '输入标题新建工作项'"
        :aria-label="canSearch ? '搜索或新建工作项' : '输入标题新建工作项'"
        clearable
        @keydown.enter.prevent="!canSearch && create()"
      />
      <el-popover
        v-if="canSearch"
        v-model:visible="fieldsOpen"
        :teleported="false"
        trigger="click"
        placement="bottom-end"
        :width="240"
        popper-class="connect-fields-popper"
      >
        <template #reference>
          <button
            type="button"
            class="connect-picker__icon connect-picker__filter"
            :class="{ 'is-active': !isDefaultConnectSearch(fields) }"
            aria-label="选择搜索字段"
          >
            <svg
              viewBox="0 0 16 16"
              aria-hidden="true"
            ><path d="M2.5 4.5h6m3 0h2M2.5 11.5h2m3 0h6" /><circle
              cx="10"
              cy="4.5"
              r="1.5"
            /><circle
              cx="6"
              cy="11.5"
              r="1.5"
            /></svg>
          </button>
        </template>
        <connect-search-fields-menu v-model="fields" />
      </el-popover>
    </div>
    <p
      v-if="notice"
      class="connect-picker__notice"
    >
      {{ notice }}
    </p>
    <inline-problem
      v-if="problem"
      :problem="problem"
    />
    <div
      ref="list"
      class="connect-picker__list"
      :aria-busy="loading"
    >
      <template v-if="connected.length">
        <p class="connect-picker__caption">
          已连接 · {{ forward ? connected.length : total }}
        </p>
        <transition-group
          name="connect-picker-row"
          tag="div"
          class="connect-picker__rows"
        >
          <div
            v-for="connection in connected"
            :key="connection.id"
            class="connect-picker__row work-item-accent-bar is-connected"
            :style="{ '--work-item-accent': color(card(connection)) }"
            @click="emit('openCard', connection)"
          >
            <el-checkbox
              :model-value="!busy.has(card(connection).workItemId)"
              :disabled="readOnly || !connection.capabilities.canUnlink || busy.has(card(connection).workItemId)"
              :aria-label="`解除连接：${card(connection).title}`"
              @click.stop
              @change="unlink(connection)"
            />
            <span class="connect-picker__text">
              <strong :class="{ 'is-archived': card(connection).archived }">{{ card(connection).title }}</strong>
              <small>{{ card(connection).itemNo }}<template v-if="column && column.targets.length > 1"> · {{ card(connection).projectName }}</template><template v-if="card(connection).archived"> · 已归档</template></small>
            </span>
            <el-icon
              class="connect-picker__open"
              aria-hidden="true"
            >
              <top-right />
            </el-icon>
          </div>
        </transition-group>
        <button
          v-if="hiddenConnections"
          type="button"
          class="connect-picker__more"
          :disabled="extraLoading"
          @click="loadMoreConnected"
        >
          {{ extraLoading ? '正在加载…' : `加载更多已连接（还有 ${hiddenConnections} 个）` }}
        </button>
      </template>
      <p
        v-else-if="!canSearch"
        class="connect-picker__empty"
      >
        还没有连接
      </p>
      <template v-if="canSearch">
        <p class="connect-picker__caption">
          {{ query.trim() ? '搜索结果' : '全部工作项' }}
        </p>
        <section
          v-for="group in groups"
          :key="group.key"
          class="connect-picker__group"
        >
          <h4 :style="{ color: group.color }">
            {{ group.name }}<small>{{ group.items.length }}</small>
          </h4>
          <transition-group
            name="connect-picker-row"
            tag="div"
            class="connect-picker__rows"
          >
            <div
              v-for="candidate in group.items"
              :key="candidate.card.workItemId"
              class="connect-picker__row work-item-accent-bar"
              :class="{ 'is-disabled': candidate.card.archived }"
              :style="{ '--work-item-accent': group.color }"
              @click="link(candidate.card)"
            >
              <el-checkbox
                :model-value="busy.has(candidate.card.workItemId)"
                :disabled="candidate.card.archived || busy.has(candidate.card.workItemId)"
                :aria-label="`关联：${candidate.card.title}`"
                @click.stop
                @change="link(candidate.card)"
              />
              <span class="connect-picker__text">
                <strong>{{ candidate.card.title }}</strong>
                <small>{{ candidate.card.itemNo }}<template v-if="candidate.parent"> · 子项 · {{ candidate.parent.title }}</template><template v-if="candidate.card.archived"> · 已归档</template></small>
              </span>
            </div>
          </transition-group>
        </section>
        <div
          v-if="loading"
          class="connect-picker__skeleton"
          role="status"
          aria-label="正在加载工作项"
        >
          <span
            v-for="index in 3"
            :key="index"
          />
        </div>
        <p
          v-else-if="!groups.length && !problem"
          class="connect-picker__empty"
          role="status"
        >
          {{ query.trim() ? '没有匹配的工作项' : '没有可连接的工作项' }}
        </p>
        <div
          ref="sentinel"
          class="connect-picker__sentinel"
          aria-hidden="true"
        />
        <button
          v-if="!observable && page + 1 < totalPages"
          type="button"
          class="connect-picker__more"
          @click="load(true)"
        >
          加载更多
        </button>
      </template>
    </div>
    <div
      v-if="undo"
      class="connect-picker__undo"
      role="status"
    >
      <span>已解除与「{{ undo.card.title }}」的连接</span>
      <button
        type="button"
        @click="restore"
      >
        撤销
      </button>
    </div>
    <div
      v-if="canCreate && query.trim()"
      class="connect-picker__add"
    >
      <button
        type="button"
        class="connect-picker__add-button"
        :disabled="creating"
        @click="create"
      >
        <span
          class="connect-picker__add-plus"
          aria-hidden="true"
        >+</span>
        <span>新建工作项</span>
        <span class="connect-picker__add-title">「{{ query.trim() }}」</span>
      </button>
      <el-dropdown
        v-if="options && options.categories.length > 1"
        :teleported="false"
        trigger="click"
        placement="top-end"
        @command="value => contentId = value"
      >
        <button
          type="button"
          class="connect-picker__category"
          :style="{ '--category-color': category ? workItemLabelColorValue(category.colorToken) : undefined }"
          aria-label="新建工作项的类别"
        >
          {{ category?.name ?? '类别' }}<el-icon><arrow-down /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-for="option in options.categories"
              :key="option.id"
              :command="option.id"
            >
              {{ option.name }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </section>
</template>

<style scoped>
.connect-picker { display: grid; gap: 10px; color: var(--yp-text-primary); outline: none; }
.connect-picker__header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.connect-picker__header h3 { margin: 0; font-family: var(--yp-font-heading); font-size: 16px; font-weight: 600; }
.connect-picker__tools { display: flex; align-items: center; gap: 4px; }
.connect-picker__icon { display: inline-grid; width: 30px; height: 30px; flex-shrink: 0; place-items: center; padding: 0; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-secondary); cursor: pointer; }
.connect-picker__icon svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 1.4; stroke-linecap: round; stroke-linejoin: round; }
.connect-picker__icon:hover { background: var(--yp-bg-hover); color: var(--yp-text-primary); }
.connect-picker__icon.is-active { background: var(--yp-bg-selected); color: var(--yp-link); }
.connect-picker__settings { display: inline-flex; align-items: center; gap: 4px; height: 30px; padding: 0 8px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-secondary); font: inherit; font-size: 13px; cursor: pointer; }
.connect-picker__settings:hover { background: var(--yp-bg-hover); color: var(--yp-text-primary); }
.connect-picker__project { margin: 0; color: var(--yp-text-primary); font-size: 15px; font-weight: 500; }
.connect-picker__project span { color: var(--yp-text-muted); font-size: 12px; font-weight: 400; }
.connect-picker__tabs { display: flex; gap: 16px; overflow-x: auto; border-bottom: 1px solid var(--yp-border-subtle); }
.connect-picker__tabs button { display: inline-flex; flex-shrink: 0; align-items: center; gap: 4px; padding: 6px 0; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--yp-text-secondary); font: inherit; font-size: 13px; cursor: pointer; }
.connect-picker__tabs button[aria-selected="true"] { border-bottom-color: var(--yp-action-primary); color: var(--yp-text-primary); font-weight: 500; }
.connect-picker__tabs small { min-width: 16px; padding: 0 4px; border-radius: var(--yp-radius-pill); background: var(--yp-bg-sunken); color: var(--yp-text-muted); font-size: 11px; text-align: center; }
.connect-picker__search { display: flex; align-items: center; gap: 6px; }
.connect-picker__filter.is-active { position: relative; }
.connect-picker__filter.is-active::after { position: absolute; top: 5px; right: 5px; width: 6px; height: 6px; border-radius: 50%; background: var(--yp-action-primary); content: ''; }
.connect-picker__notice { margin: 0; padding: 8px 10px; border-radius: var(--yp-radius-sm); background: var(--yp-bg-sunken); color: var(--yp-text-secondary); font-size: 12px; line-height: 1.6; }
.connect-picker__list { display: grid; align-content: start; gap: 2px; max-height: min(340px, calc(100vh - 260px)); min-height: 72px; margin: 0 -4px; padding: 0 4px; overflow-y: auto; overscroll-behavior: contain; }
.connect-picker__caption { margin: 8px 0 4px; color: var(--yp-text-muted); font-size: 12px; }
.connect-picker__caption:first-child { margin-top: 0; }
.connect-picker__group h4 { display: flex; align-items: baseline; gap: 6px; margin: 8px 0 4px; font-family: var(--yp-font-heading); font-size: 14px; font-weight: 600; }
.connect-picker__group h4 small { color: var(--yp-text-muted); font-size: 11px; font-weight: 400; }
.connect-picker__rows { display: grid; gap: 4px; }
.connect-picker__row { display: flex; align-items: center; gap: 10px; min-height: 36px; padding: 0 10px 0 16px; border: 1px solid var(--yp-monday-grid-border, var(--yp-border-subtle)); background: var(--yp-bg-surface); cursor: pointer; transition: background-color var(--yp-motion-fast) var(--yp-ease-standard), border-color var(--yp-motion-fast) var(--yp-ease-standard); }
.connect-picker__row::before { top: -1px; bottom: -1px; left: -1px; }
.connect-picker__row:hover { background: var(--yp-bg-hover); }
.connect-picker__row.is-connected { border-color: color-mix(in srgb, var(--yp-action-primary) 30%, var(--yp-border-subtle)); background: color-mix(in srgb, var(--yp-bg-selected) 55%, var(--yp-bg-surface)); }
.connect-picker__row.is-disabled { cursor: not-allowed; opacity: .6; }
.connect-picker__text { display: flex; flex: 1; align-items: baseline; gap: 8px; min-width: 0; }
.connect-picker__text strong { overflow: hidden; font-size: 13px; font-weight: 400; text-overflow: ellipsis; white-space: nowrap; }
.connect-picker__text strong.is-archived { color: var(--yp-text-muted); }
.connect-picker__text small { flex-shrink: 0; max-width: 45%; overflow: hidden; color: var(--yp-text-muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.connect-picker__open { flex-shrink: 0; color: var(--yp-text-muted); opacity: 0; transition: opacity var(--yp-motion-fast) var(--yp-ease-standard); }
.connect-picker__row:hover .connect-picker__open { opacity: 1; }
.connect-picker__more { justify-self: start; margin: 4px 0; padding: 4px 6px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-link); font: inherit; font-size: 12px; cursor: pointer; }
.connect-picker__more:hover { background: var(--yp-bg-hover); }
.connect-picker__empty { margin: 12px 0; color: var(--yp-text-muted); font-size: 13px; text-align: center; }
.connect-picker__skeleton { display: grid; gap: 4px; }
.connect-picker__skeleton span { height: 36px; border-radius: var(--work-item-hierarchy-corner-radius, 6px); background: linear-gradient(90deg, var(--yp-bg-sunken), var(--yp-bg-hover), var(--yp-bg-sunken)); background-size: 200% 100%; animation: connect-picker-shimmer 1.2s linear infinite; }
.connect-picker__sentinel { height: 1px; }
.connect-picker__undo { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 10px; border-radius: var(--yp-radius-sm); background: var(--yp-bg-tooltip); color: var(--yp-text-tooltip); font-size: 12px; animation: connect-picker-rise var(--yp-motion-popover) var(--yp-ease-standard) both; }
.connect-picker__undo span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.connect-picker__undo button { flex-shrink: 0; padding: 2px 6px; border: 0; border-radius: var(--yp-radius-xs); background: transparent; color: var(--yp-brand-glow); font: inherit; font-weight: 600; cursor: pointer; }
.connect-picker__add { display: flex; align-items: center; gap: 6px; margin: 0 -14px -14px; padding: 8px 14px; border-top: 1px solid var(--yp-border-subtle); background: var(--yp-bg-surface); border-radius: 0 0 var(--yp-radius-lg) var(--yp-radius-lg); animation: connect-picker-rise var(--yp-motion-popover) var(--yp-ease-standard) both; }
.connect-picker__add-button { display: flex; flex: 1; align-items: center; gap: 8px; min-width: 0; min-height: 34px; padding: 0 8px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-primary); font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.connect-picker__add-button:hover:not(:disabled) { background: var(--yp-bg-hover); }
.connect-picker__add-button:disabled { opacity: .6; cursor: progress; }
.connect-picker__add-plus { display: inline-grid; width: 20px; height: 20px; flex-shrink: 0; place-items: center; border-radius: 50%; background: var(--yp-action-primary); color: var(--yp-status-blue-foreground); font-size: 15px; line-height: 1; }
.connect-picker__add-title { overflow: hidden; color: var(--yp-text-secondary); text-overflow: ellipsis; white-space: nowrap; }
.connect-picker__category { display: inline-flex; align-items: center; gap: 4px; height: 26px; padding: 0 8px; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-pill); background: color-mix(in srgb, var(--category-color, var(--yp-bg-sunken)) 16%, var(--yp-bg-surface)); color: var(--yp-text-secondary); font: inherit; font-size: 12px; cursor: pointer; }
.connect-picker-row-enter-active, .connect-picker-row-leave-active { transition: opacity var(--yp-motion-popover) var(--yp-ease-standard), transform var(--yp-motion-popover) var(--yp-ease-standard); }
.connect-picker-row-enter-from { opacity: 0; transform: translateY(-4px); }
.connect-picker-row-leave-to { opacity: 0; transform: translateX(8px); }
.connect-picker-row-move { transition: transform var(--yp-motion-popover) var(--yp-ease-standard); }
@keyframes connect-picker-shimmer { to { background-position: -200% 0; } }
@keyframes connect-picker-rise { from { opacity: 0; transform: translateY(4px); } }
@media (prefers-reduced-motion: reduce) {
  .connect-picker-row-enter-active, .connect-picker-row-leave-active, .connect-picker-row-move { transition: none; }
  .connect-picker__skeleton span, .connect-picker__undo, .connect-picker__add { animation: none; }
}
</style>

<style>
.connect-fields-popper.el-popover.el-popper { padding: 12px; border-radius: var(--yp-radius-lg); box-shadow: var(--yp-shadow-popover); }
</style>
