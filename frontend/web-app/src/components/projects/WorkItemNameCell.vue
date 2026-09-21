<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElInput, ElMessage, type InputInstance } from 'element-plus'
import { readCsrfToken, type ProjectWorkItemListItem, type WorkItemDetail } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { isWorkItemViewControl } from './workItemViewControls'

const props = defineProps<{
  item: ProjectWorkItemListItem
  strictVersion?: boolean
  disabled?: boolean
  selected?: boolean
  create?: ((title: string) => Promise<boolean>) | undefined
}>()
const emit = defineEmits<{
  open: []
  updated: [id: string, detail: WorkItemDetail]
  editing: [active: boolean]
  cancel: []
}>()
const root = ref<HTMLElement>()
const input = ref<InputInstance>()
const editing = ref(Boolean(props.create))
const saving = ref(false)
const composing = ref(false)
const title = ref(props.item.title)
let disposed = false
let viewControlInteraction = false

async function focus(select = false): Promise<void> {
  await nextTick()
  if (disposed) return
  input.value?.input?.focus({ preventScroll: true })
  if (select) input.value?.select()
}

function start(): void {
  if (props.disabled || !props.item.capabilities.canEditFields || editing.value) return
  title.value = props.item.title
  editing.value = true
  emit('editing', true)
  void focus()
}

function finish(): void {
  editing.value = false
  emit('editing', false)
}

function cancel(): void {
  if (saving.value) return
  finish()
  if (props.create) emit('cancel')
}

async function save(): Promise<void> {
  if (!editing.value || saving.value || composing.value || disposed) return
  const value = title.value.trim()
  if (!value) { cancel(); return }
  if (!props.create && value === props.item.title) { finish(); return }
  saving.value = true
  try {
    if (props.create) {
      if (!await props.create(value)) return
    } else {
      const token = readCsrfToken()
      if (!token) throw new Error('缺少 CSRF 凭据，请刷新后重试。')
      // 列表没有描述、备注和时间线；完整更新必须保留最新详情中的这些字段。
      const current = await workItemsApi.getWorkItem({ workItemId: props.item.id })
      if (props.strictVersion && current.etag !== props.item.etag) {
        emit('updated', props.item.id, current); finish()
        ElMessage.warning('此工作项已更新，已载入最新内容，请确认后重新编辑。'); return
      }
      const updated = await workItemsApi.updateWorkItem({
        workItemId: props.item.id, xXSRFTOKEN: token, ifMatch: current.etag,
        workItemUpdateRequest: {
          title: value, priority: current.priority, assigneeUserId: current.assigneeUserId,
          description: current.description, notes: current.notes,
          timelineStartDate: current.timelineStartDate, timelineEndDate: current.timelineEndDate,
          dueDate: current.dueDate, dueTime: current.dueTime ?? null,
        },
      })
      emit('updated', props.item.id, updated)
    }
    finish()
  } catch (reason) {
    const problem = await toApiProblem(reason)
    ElMessage.error(problemMessage(problem))
    if (!props.create && problem.kind === 'response' && problem.status === 412) {
      try { emit('updated', props.item.id, await workItemsApi.getWorkItem({ workItemId: props.item.id })); finish() } catch { /* Keep the draft available if reloading fails. */ }
    }
  } finally {
    saving.value = false
    if (editing.value) void focus()
  }
}

function outside(event: PointerEvent): void {
  viewControlInteraction = isWorkItemViewControl(event.target)
  if (viewControlInteraction) return
  if (!root.value?.contains(event.target as Node)) void save()
}

function blur(event: FocusEvent): void {
  if (!viewControlInteraction && !isWorkItemViewControl(event.relatedTarget)) void save()
}

async function compositionEnd(): Promise<void> {
  // ElInput 在 compositionend 发出后才排队更新 v-model，保存需等该更新完成。
  await nextTick()
  await nextTick()
  composing.value = false
  if (!viewControlInteraction && document.activeElement !== input.value?.input) void save()
}

function keydown(event: Event | KeyboardEvent): void {
  if (!(event instanceof KeyboardEvent)) return
  if (event.isComposing || event.keyCode === 229) return
  if (event.key === 'Enter') { event.preventDefault(); void save() }
  if (event.key === 'Escape') { event.preventDefault(); cancel() }
}

onMounted(() => {
  document.addEventListener('pointerdown', outside, true)
  if (editing.value) {
    emit('editing', true)
    void focus(true)
    root.value?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
  }
})
onBeforeUnmount(() => {
  disposed = true
  document.removeEventListener('pointerdown', outside, true)
  if (editing.value) emit('editing', false)
})
</script>

<template>
  <div
    ref="root"
    class="work-item-name-cell"
    :class="{ 'work-item-name-cell--editing': editing, 'monday-cell--selected': selected || editing }"
    :aria-busy="saving"
  >
    <slot name="prefix" />
    <el-input
      v-if="editing"
      ref="input"
      v-model="title"
      class="work-item-name-input"
      :aria-label="create ? '新工作项名称' : '工作项名称'"
      :placeholder="create ? '*新工作项' : ''"
      maxlength="300"
      :readonly="saving"
      @pointerdown.stop
      @click.stop
      @dragstart.stop.prevent
      @keydown.stop="keydown"
      @blur="blur"
      @focus="viewControlInteraction = false"
      @compositionstart="composing = true"
      @compositionend="compositionEnd"
    />
    <template v-else>
      <button
        type="button"
        class="work-item-title-text"
        :disabled="disabled || !item.capabilities.canEditFields"
        :aria-label="`编辑名称：${item.title}`"
        @click.stop="start"
      >
        {{ item.title }}
      </button>
      <slot name="suffix" />
      <button
        type="button"
        class="work-item-detail-button"
        :aria-label="`打开工作项详情：${item.title}`"
        @pointerdown.stop
        @dragstart.stop.prevent
        @click.stop="emit('open')"
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M11.5 3.5h5v5m0-5-6 6M8 4.5H4.5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1V12" />
        </svg>
      </button>
    </template>
  </div>
</template>

<style scoped>
.work-item-name-cell { position: relative; display: flex; flex: 1 1 auto; min-width: 0; height: 100%; align-items: center; box-sizing: border-box; padding: 0 8px; color: var(--yp-text-primary); }
.work-item-name-cell.work-item-name-cell--editing { background: var(--yp-bg-surface); cursor: text; touch-action: auto; user-select: text; }
.work-item-name-cell.monday-cell--selected::after { position: absolute; z-index: 8; inset: 0; border: 1px solid var(--yp-action-primary); box-sizing: border-box; content: ''; pointer-events: none; }
.work-item-title-text { flex: 0 1 auto; min-width: 0; width: max-content; padding: 0; border: 0; background: transparent; color: inherit; font: inherit; font-size: 13.5px; font-weight: 500; line-height: normal; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: text; }
.work-item-title-text:disabled { cursor: inherit; }
.work-item-title-text:focus-visible { outline: 1px solid var(--yp-action-primary); outline-offset: 2px; }
.work-item-name-input { flex: 1 1 auto; width: 0; min-width: 0; height: 100%; --el-input-bg-color: var(--yp-bg-surface); --el-input-text-color: var(--yp-text-primary); --el-input-placeholder-color: color-mix(in srgb, var(--yp-text-muted) 60%, var(--yp-bg-surface)); }
.work-item-name-cell .work-item-name-input :deep(.el-input__wrapper) { height: 100%; min-height: 0; padding: 0; border-radius: 0; box-shadow: none; }
.work-item-name-cell .work-item-name-input :deep(.el-input__wrapper:has(input:focus-visible)) { outline: none; }
.work-item-name-input :deep(.el-input__inner) { height: 100%; min-height: 0; padding: 0; font: inherit; font-size: 13.5px; font-weight: 500; line-height: normal; cursor: text; }
.work-item-detail-button { display: inline-flex; flex: 0 0 24px; width: 24px; height: 24px; align-items: center; justify-content: center; margin-left: auto; padding: 0; border: 0; border-radius: 4px; background: transparent; color: var(--yp-text-secondary); cursor: pointer; opacity: 0; }
.work-item-name-cell:hover .work-item-detail-button, .work-item-name-cell:focus-within .work-item-detail-button { opacity: 1; }
.work-item-detail-button:hover { background: var(--yp-bg-selected); color: var(--yp-action-primary); }
.work-item-detail-button:focus-visible { outline: 1px solid var(--yp-action-primary); outline-offset: 1px; }
@media (hover: none) { .work-item-detail-button { opacity: 1; } }
</style>
