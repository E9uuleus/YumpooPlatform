<script setup lang="ts" generic="TItem extends { id: string }">
import { ElInput } from 'element-plus'
import type { WorkItemLabelColorToken } from '@yumpoo/api-client'
import { onBeforeUnmount, ref } from 'vue'
import type { ApiProblem } from '../../api/problems'
import InlineProblem from '../InlineProblem.vue'
import WorkItemKanbanSkeleton from './WorkItemKanbanSkeleton.vue'
import { workItemLabelColorValue } from './workItemLabelColors'

export interface KanbanBoardStatus { statusCode: string; displayName: string; colorToken?: WorkItemLabelColorToken; active: boolean }
export interface KanbanBoardLane<T> { items: T[]; nextCursor: string | null; loading: boolean; error?: ApiProblem | undefined }
export interface KanbanBoardDraft { open: boolean; title: string; saving: boolean; error?: ApiProblem | undefined }

/**
 * 看板只负责展示：状态色块列头、各列独立滚动、列内懒加载哨兵、拖放目标与列尾新建行。
 * 数据加载、状态迁移与新建逻辑都在父组件，卡片通过 #card 插槽由父组件渲染。
 */
const props = defineProps<{
  statuses: readonly KanbanBoardStatus[]
  lane: (statusCode: string) => KanbanBoardLane<TItem>
  /** 每列的工作项总数（与当前筛选一致）；缺失时显示已加载数量。 */
  counts?: ReadonlyMap<string, number> | undefined
  /** 返回不能在该列新建的原因；空字符串表示可以新建。 */
  createDisabledReason: (status: KanbanBoardStatus) => string
  creatingTop: (statusCode: string) => boolean
  draft: (statusCode: string) => KanbanBoardDraft
  /** 正在拖动的卡片时允许放下。 */
  dragging: boolean
}>()
const emit = defineEmits<{
  loadMore: [statusCode: string]
  drop: [statusCode: string]
  createTop: [statusCode: string]
  openDraft: [statusCode: string]
  draftKeydown: [statusCode: string, event: Event | KeyboardEvent]
  cancelDraft: [statusCode: string]
  draftInput: [statusCode: string, input: unknown]
}>()
defineSlots<{ card(props: { item: TItem; status: KanbanBoardStatus }): unknown }>()

const dropTarget = ref<string>()
const bodies = new Map<string, HTMLElement>()
const observers = new Map<string, IntersectionObserver>()
const sentinels = new Map<string, HTMLElement>()

function laneColor(status: KanbanBoardStatus): string { return workItemLabelColorValue(status.colorToken) }
function countLabel(status: KanbanBoardStatus): string {
  const total = props.counts?.get(status.statusCode)
  if (total !== undefined) return String(total)
  const state = props.lane(status.statusCode)
  return `${state.items.length}${state.nextCursor ? '+' : ''}`
}

/** 每列以自身滚动区为根观察哨兵，提前 240px 加载下一页。 */
function observer(statusCode: string): IntersectionObserver | undefined {
  const root = bodies.get(statusCode)
  if (!root || typeof IntersectionObserver === 'undefined') return undefined
  let current = observers.get(statusCode)
  if (!current) {
    current = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) emit('loadMore', statusCode)
    }, { root, rootMargin: '0px 0px 240px 0px' })
    observers.set(statusCode, current)
  }
  return current
}
function setBody(statusCode: string, element: unknown): void {
  if (element instanceof HTMLElement) {
    bodies.set(statusCode, element)
    const sentinel = sentinels.get(statusCode)
    if (sentinel) observer(statusCode)?.observe(sentinel)
  } else {
    observers.get(statusCode)?.disconnect()
    observers.delete(statusCode)
    bodies.delete(statusCode)
  }
}
function setSentinel(statusCode: string, element: unknown): void {
  const previous = sentinels.get(statusCode)
  if (previous && previous !== element) observers.get(statusCode)?.unobserve(previous)
  if (element instanceof HTMLElement) {
    sentinels.set(statusCode, element)
    observer(statusCode)?.observe(element)
  } else sentinels.delete(statusCode)
}
onBeforeUnmount(() => { observers.forEach(item => item.disconnect()); observers.clear() })

function onDragOver(status: KanbanBoardStatus, event: DragEvent): void {
  if (!props.dragging || !status.active) return
  event.preventDefault()
  dropTarget.value = status.statusCode
}
function onDragLeave(status: KanbanBoardStatus, event: DragEvent): void {
  const next = event.relatedTarget
  if (next instanceof Node && (event.currentTarget as HTMLElement).contains(next)) return
  if (dropTarget.value === status.statusCode) dropTarget.value = undefined
}
function onDrop(status: KanbanBoardStatus): void {
  dropTarget.value = undefined
  if (status.active) emit('drop', status.statusCode)
}
</script>

<template>
  <div
    class="kanban-board"
    @dragend="dropTarget = undefined"
  >
    <section
      v-for="status in statuses"
      :key="status.statusCode"
      class="kanban-lane"
      :class="{ 'kanban-lane--drop': dropTarget === status.statusCode, 'kanban-lane--inactive': !status.active }"
      :style="{ '--kanban-lane-color': laneColor(status) }"
      :aria-label="`${status.displayName}，${countLabel(status)} 个工作项`"
      @dragover="onDragOver(status, $event)"
      @dragleave="onDragLeave(status, $event)"
      @drop.prevent="onDrop(status)"
    >
      <header class="kanban-lane__header">
        <strong class="kanban-lane__title">{{ status.displayName }}</strong>
        <span class="kanban-lane__count">{{ countLabel(status) }}</span>
        <button
          v-if="!createDisabledReason(status)"
          type="button"
          class="kanban-lane__create"
          :disabled="creatingTop(status.statusCode)"
          :aria-label="`在「${status.displayName}」新建工作项`"
          :title="`新建工作项`"
          @click="emit('createTop', status.statusCode)"
        >
          <svg
            width="16"
            height="16"
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            aria-hidden="true"
          ><path d="M10 4v12M4 10h12" /></svg>
        </button>
      </header>
      <div
        :ref="element => setBody(status.statusCode, element)"
        class="kanban-lane__body"
      >
        <inline-problem
          v-if="lane(status.statusCode).error"
          :problem="lane(status.statusCode).error!"
        />
        <work-item-kanban-skeleton v-if="lane(status.statusCode).loading && !lane(status.statusCode).items.length" />
        <template
          v-for="item in lane(status.statusCode).items"
          :key="item.id"
        >
          <slot
            name="card"
            :item="item"
            :status="status"
          />
        </template>
        <div
          v-if="lane(status.statusCode).nextCursor"
          :ref="element => setSentinel(status.statusCode, element)"
          class="lane-cursor-sentinel incremental-state"
          :class="{ 'lane-cursor-sentinel--loading': lane(status.statusCode).loading }"
          :data-status="status.statusCode"
        >
          <work-item-kanban-skeleton
            v-if="lane(status.statusCode).loading"
            :cards="1"
            label="正在加载更多工作项"
          />
          <template v-else>
            继续滚动以加载更多
          </template>
        </div>
        <template v-else-if="!createDisabledReason(status) && !lane(status.statusCode).loading">
          <div
            v-if="draft(status.statusCode).open"
            class="kanban-lane__draft"
          >
            <el-input
              :ref="input => emit('draftInput', status.statusCode, input)"
              v-model="draft(status.statusCode).title"
              placeholder="工作项名称，回车创建"
              :aria-label="`在「${status.displayName}」新建工作项的名称`"
              maxlength="300"
              :readonly="draft(status.statusCode).saving"
              @keydown="emit('draftKeydown', status.statusCode, $event)"
              @blur="!draft(status.statusCode).title.trim() && emit('cancelDraft', status.statusCode)"
            />
            <inline-problem
              v-if="draft(status.statusCode).error"
              :problem="draft(status.statusCode).error!"
            />
          </div>
          <button
            v-else
            type="button"
            class="kanban-lane__add"
            @click="emit('openDraft', status.statusCode)"
          >
            + 新建工作项
          </button>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* 页面高度固定：看板占满剩余高度，横向滚动；每列独立纵向滚动。 */
.kanban-board {
  display: flex;
  flex: 1 1 0;
  min-height: 0;
  align-items: stretch;
  gap: 16px;
  padding: 4px 0 8px;
  overflow-x: auto;
  overflow-y: hidden;
}

.kanban-lane {
  display: flex;
  flex: 0 0 272px;
  min-height: 0;
  flex-direction: column;
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-sunken);
  transition: box-shadow var(--yp-motion-fast) var(--yp-ease-standard);
}
.kanban-lane--drop { box-shadow: inset 0 0 0 2px var(--kanban-lane-color); }

.kanban-lane__header {
  display: flex;
  flex: none;
  height: 36px;
  align-items: center;
  gap: 8px;
  padding: 0 6px 0 12px;
  border-radius: var(--yp-radius-md) var(--yp-radius-md) 0 0;
  background: var(--kanban-lane-color);
  color: var(--yp-on-solid);
}
.kanban-lane--inactive .kanban-lane__header { opacity: .7; }
.kanban-lane__title { min-width: 0; overflow: hidden; font-size: 14px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.kanban-lane__count { flex: none; font-size: 14px; opacity: .9; }
.kanban-lane__create {
  display: inline-grid;
  width: 26px;
  height: 26px;
  flex: none;
  place-items: center;
  margin-left: auto;
  padding: 0;
  border: 0;
  border-radius: var(--yp-radius-sm);
  background: transparent;
  color: var(--yp-on-solid);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--yp-motion-fast) var(--yp-ease-standard), background-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.kanban-lane:hover .kanban-lane__create, .kanban-lane__create:focus-visible { opacity: 1; }
.kanban-lane__create:hover:not(:disabled) { background: color-mix(in srgb, var(--yp-on-solid) 22%, transparent); }
.kanban-lane__create:focus-visible { outline: 2px solid var(--yp-on-solid); outline-offset: -2px; }
.kanban-lane__create:disabled { cursor: progress; }
@media (hover: none) { .kanban-lane__create { opacity: 1; } }

.kanban-lane__body {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  flex-direction: column;
  gap: 10px;
  padding: 10px 8px 12px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.kanban-lane__add {
  display: flex;
  flex: none;
  min-height: 36px;
  align-items: center;
  padding: 0 12px;
  border: 0;
  border-radius: var(--yp-radius-md);
  background: transparent;
  color: var(--yp-text-secondary);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--yp-motion-fast) var(--yp-ease-standard), background-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.kanban-lane:hover .kanban-lane__add, .kanban-lane__add:focus-visible { opacity: 1; }
.kanban-lane__add:hover { background: var(--yp-fill-neutral-active); color: var(--yp-text-primary); }
.kanban-lane__add:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: -2px; }
@media (hover: none) { .kanban-lane__add { opacity: 1; } }
.kanban-lane__draft { display: grid; flex: none; gap: 6px; }

.lane-cursor-sentinel { flex: none; color: var(--yp-text-muted); font-size: 12px; text-align: center; }
</style>
