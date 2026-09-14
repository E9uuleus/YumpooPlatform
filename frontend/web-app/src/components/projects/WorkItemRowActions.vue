<script setup lang="ts">
import { computed, nextTick, ref, shallowRef } from 'vue'
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElMessage, ElMessageBox, ElPopover } from 'element-plus'
import { readCsrfToken, WorkItemViewType, type ProjectWorkItemListItem, type WorkItemDetail } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import WorkItemParentPicker from './WorkItemParentPicker.vue'

const props = defineProps<{
  item: ProjectWorkItemListItem
  parentId?: string | undefined
  canCreate: boolean
  sorted: boolean
  disabled?: boolean
  beforeRemove?: (() => Promise<boolean>) | undefined
  orderItems?: ((item: ProjectWorkItemListItem, edge: 'top' | 'bottom') => Promise<ProjectWorkItemListItem[]>) | undefined
}>()
const emit = defineEmits<{
  open: [item: ProjectWorkItemListItem]
  addSubitem: [item: ProjectWorkItemListItem]
  createBelow: [item: ProjectWorkItemListItem]
  moved: [item: ProjectWorkItemListItem]
  changed: [affectedIds: string[]]
  removed: [item: ProjectWorkItemListItem]
}>()
const open = ref(false)
const busy = ref(false)
const choosingParent = ref(false)
const converting = ref(false)
const dropdown = ref<InstanceType<typeof ElDropdown>>()
const parentMenuElement = shallowRef<HTMLElement>()
const parentPicker = ref<InstanceType<typeof WorkItemParentPicker>>()
const locked = computed(() => busy.value || converting.value || props.disabled)
const moveReason = computed(() => props.sorted ? '请先清除排序，再调整手动顺序' : undefined)
const convertReason = computed(() => props.item.subitemCount > 0 ? '已有子工作项，不能转为子工作项' : undefined)

function closeMenu(): void {
  choosingParent.value = false
  dropdown.value?.handleClose()
}

function menuVisibility(visible: boolean): void {
  open.value = visible
  if (!visible) choosingParent.value = false
}

async function showParentPicker(event: Event, focusSearch = false): Promise<void> {
  if (locked.value || !props.item.capabilities.canEditFields || convertReason.value) return
  if (event.currentTarget instanceof HTMLElement) parentMenuElement.value = event.currentTarget
  choosingParent.value = true
  if (focusSearch) {
    await nextTick()
    parentPicker.value?.focusSearch()
  }
}

function closeParentPicker(): void {
  if (converting.value) return
  choosingParent.value = false
  parentMenuElement.value?.focus()
}

function openItem(): void { if (!locked.value) { closeMenu(); emit('open', props.item) } }
function addSubitem(): void { if (!locked.value) { closeMenu(); emit('addSubitem', props.item) } }
function converted(ids: string[]): void { closeMenu(); emit('changed', ids) }

function csrf(): string {
  const token = readCsrfToken()
  if (!token) throw new Error('缺少 CSRF 凭据，请刷新后重试。')
  return token
}

async function run(action: () => Promise<void>): Promise<void> {
  if (locked.value) return
  closeMenu()
  busy.value = true
  try { await action() } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close') {
      ElMessage.error(problemMessage(await toApiProblem(reason)))
      emit('changed', [props.item.id, ...(props.parentId ? [props.parentId] : [])])
    }
  } finally { busy.value = false }
}

async function copyLink(): Promise<void> {
  await run(async () => {
    const url = new URL(`/projects/${encodeURIComponent(props.item.projectId)}/overview`, window.location.origin)
    url.searchParams.set('workItemId', props.item.id)
    try {
      await navigator.clipboard.writeText(url.href)
      ElMessage.success('工作项链接已复制')
    } catch {
      await ElMessageBox.prompt('自动复制不可用，请复制下方链接。', '工作项链接', {
        inputValue: url.href, confirmButtonText: '完成', showCancelButton: false,
      })
    }
  })
}

async function move(item: Pick<WorkItemDetail, 'id' | 'etag'>, previousId: string | null, nextId: string | null): Promise<void> {
  const common = { xXSRFTOKEN: csrf(), ifMatch: item.etag, idempotencyKey: crypto.randomUUID(),
    projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: previousId, nextVisibleWorkItemId: nextId } }
  if (props.parentId) {
    await workItemsApi.moveWorkItemSubitemOrder({ ...common, parentWorkItemId: props.parentId, subitemId: item.id })
  } else {
    await workItemsApi.moveProjectWorkItemOrder({ ...common, projectId: props.item.projectId, workItemId: item.id })
  }
}

async function moveTo(edge: 'top' | 'bottom'): Promise<void> {
  if (props.sorted || !props.item.capabilities.canMoveInProjectOrder) return
  await run(async () => {
    let siblings: ProjectWorkItemListItem[] = []
    if (props.orderItems) {
      siblings = await props.orderItems(props.item, edge)
    } else if (props.parentId) {
      siblings = (await workItemsApi.listWorkItemSubitems({ parentWorkItemId: props.parentId })).items
    } else {
      let cursor: string | null = null
      do {
        const page = await workItemsApi.listProjectWorkItems({ projectId: props.item.projectId,
          view: WorkItemViewType.Table, limit: edge === 'top' ? 2 : 100, ...(cursor ? { cursor } : {}) })
        siblings.push(...page.items)
        cursor = page.nextCursor
        if (edge === 'top') break
      } while (cursor)
    }
    const others = siblings.filter(item => item.id !== props.item.id)
    const anchor = edge === 'top' ? others[0] : others.at(-1)
    if (anchor) await move(props.item, edge === 'bottom' ? anchor.id : null, edge === 'top' ? anchor.id : null)
    emit('moved', props.item)
  })
}

function createBelow(): void {
  if (!props.canCreate || props.sorted || locked.value) return
  closeMenu()
  emit('createBelow', props.item)
}

async function remove(archive: boolean): Promise<void> {
  if (!props.item.capabilities.canDelete) return
  await run(async () => {
    if (props.beforeRemove && !await props.beforeRemove()) return
    const action = archive ? '归档' : '删除'
    await ElMessageBox.confirm(
      `确定${action}“${props.item.title}”？${archive ? '归档后将从列表隐藏，可通过工作项链接恢复。' : '工作项将移出列表，历史记录将保留。'}${props.item.subitemCount ? '其子工作项将随父项从主表隐藏，父子关系保留。' : ''}`,
      `${action}工作项`, { confirmButtonText: action, cancelButtonText: '取消', type: 'warning' },
    )
    const common = { workItemId: props.item.id, xXSRFTOKEN: csrf(), ifMatch: props.item.etag, idempotencyKey: crypto.randomUUID() }
    if (archive) await workItemsApi.archiveWorkItem(common)
    else await workItemsApi.deleteWorkItem({ ...common, workItemDeleteRequest: { reason: '通过工作项行菜单删除' } })
    emit('removed', props.item)
    emit('changed', [props.item.id, ...(props.parentId ? [props.parentId] : [])])
    ElMessage.success(`工作项已${action}`)
  })
}
</script>

<template>
  <div
    class="work-item-row-actions"
    :class="{ 'work-item-row-actions--open': open || busy }"
    @pointerdown.stop
    @click.stop
    @keydown.stop
  >
    <el-dropdown
      ref="dropdown"
      trigger="click"
      placement="bottom-start"
      :popper-options="{ modifiers: [{ name: 'flip', options: { fallbackPlacements: ['top-start', 'bottom-end', 'top-end'] } }] }"
      :hide-on-click="false"
      :disabled="busy || disabled"
      popper-class="work-item-row-menu"
      @visible-change="menuVisibility"
    >
      <button
        type="button"
        class="work-item-row-menu-trigger"
        :aria-label="`${item.title}的更多操作`"
        :disabled="locked"
        :aria-expanded="open"
        aria-haspopup="menu"
      >
        <svg
          width="18"
          height="18"
          viewBox="0 0 20 20"
          fill="currentColor"
          aria-hidden="true"
        ><circle
          cx="4"
          cy="10"
          r="1.5"
        /><circle
          cx="10"
          cy="10"
          r="1.5"
        /><circle
          cx="16"
          cy="10"
          r="1.5"
        /></svg>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item @click="openItem">
            <span class="row-action-icon">↗</span>打开工作项
          </el-dropdown-item>
          <el-dropdown-item
            divided
            @click="copyLink"
          >
            <svg
              class="row-action-icon"
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              aria-hidden="true"
            ><path d="m8 7 2-2a4 4 0 0 1 6 6l-2 2M12 13l-2 2a4 4 0 0 1-6-6l2-2M7 13l6-6" /></svg>复制工作项链接
          </el-dropdown-item>
          <el-dropdown-item
            :disabled="!item.capabilities.canMoveInProjectOrder || sorted"
            :title="moveReason"
            @click="moveTo('top')"
          >
            <span class="row-action-icon">↑</span>移动至顶部
          </el-dropdown-item>
          <el-dropdown-item
            :disabled="!item.capabilities.canMoveInProjectOrder || sorted"
            :title="moveReason"
            @click="moveTo('bottom')"
          >
            <span class="row-action-icon">↓</span>移动至底部
          </el-dropdown-item>
          <el-dropdown-item
            :disabled="!canCreate || sorted"
            :title="moveReason"
            @click="createBelow"
          >
            <span class="row-action-icon">＋</span>在下方创建新工作项
          </el-dropdown-item>
          <el-dropdown-item
            v-if="!parentId"
            divided
            :disabled="!canCreate || !item.capabilities.canEditFields"
            @click="addSubitem"
          >
            <svg
              class="row-action-icon"
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              aria-hidden="true"
            ><path d="M3 3h7v5H3zM10 12h7v5h-7zM6 8v6h4" /></svg>添加子工作项
          </el-dropdown-item>
          <el-dropdown-item
            v-if="!parentId"
            class="row-action-convert"
            :class="{ 'row-action-convert--open': choosingParent }"
            :disabled="!item.capabilities.canEditFields || Boolean(convertReason)"
            :title="convertReason"
            :aria-expanded="choosingParent"
            aria-haspopup="dialog"
            @pointermove="showParentPicker($event)"
            @click="showParentPicker($event, true)"
            @keydown.right.stop.prevent="showParentPicker($event, true)"
          >
            <span class="row-action-icon">↳</span>转为子工作项
            <svg
              class="row-action-submenu-arrow"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              aria-hidden="true"
            ><path d="m6 3 5 5-5 5" /></svg>
          </el-dropdown-item>
          <el-popover
            v-if="!parentId && parentMenuElement"
            :visible="choosingParent"
            virtual-triggering
            :virtual-ref="parentMenuElement"
            placement="right-end"
            :fallback-placements="['left-end', 'right-start', 'left-start']"
            :width="340"
            :offset="8"
            :show-arrow="false"
            :teleported="false"
            :persistent="converting"
            popper-class="work-item-parent-popover"
          >
            <work-item-parent-picker
              v-if="choosingParent || converting"
              ref="parentPicker"
              :item="item"
              @close="closeParentPicker"
              @changed="converted"
              @busy-change="converting = $event"
            />
          </el-popover>
          <el-dropdown-item
            divided
            :disabled="!item.capabilities.canDelete"
            @click="remove(true)"
          >
            <svg
              class="row-action-icon"
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              aria-hidden="true"
            ><path d="M3 3h14v4H3zM4 7v10h12V7M8 10h4" /></svg>归档
          </el-dropdown-item>
          <el-dropdown-item
            class="row-action-delete"
            :disabled="!item.capabilities.canDelete"
            @click="remove(false)"
          >
            <svg
              class="row-action-icon"
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              aria-hidden="true"
            ><path d="M3 5h14M7 5V3h6v2M5 5l1 12h8l1-12M8 8v6m4-6v6" /></svg>删除
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style>
.work-item-row-actions { display: flex; align-items: center; justify-content: center; }
.work-item-row-menu-trigger { display: inline-flex; width: 26px; height: 26px; align-items: center; justify-content: center; padding: 0; border: 0; border-radius: 4px; background: transparent; color: var(--yp-text-secondary); cursor: pointer; opacity: 0; }
tr.el-table__row:hover > td .work-item-row-menu-trigger,
tr.el-table__row.hover-row > td .work-item-row-menu-trigger,
.work-item-row-actions:focus-within .work-item-row-menu-trigger,
.work-item-row-actions--open .work-item-row-menu-trigger { opacity: 1; }
.work-item-row-menu-trigger:hover,
.work-item-row-actions--open .work-item-row-menu-trigger { background: var(--yp-bg-selected); color: var(--yp-action-primary); }
.work-item-row-menu-trigger:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: 1px; }
@media (hover: none) { .work-item-row-menu-trigger { opacity: 1; } }
.work-item-row-menu .el-dropdown-menu { min-width: 232px; padding: 8px; }
.work-item-row-menu .el-scrollbar, .work-item-row-menu .el-scrollbar__wrap { overflow: visible; }
.work-item-row-menu .el-dropdown-menu__item { min-height: 34px; padding: 0 10px; gap: 10px; border-radius: 4px; font-size: 14px; }
.work-item-row-menu .el-dropdown-menu__item--divided { margin: 7px 0 0; }
.work-item-row-menu .row-action-icon { display: inline-flex; width: 18px; height: 18px; flex: 0 0 18px; align-items: center; justify-content: center; font-size: 20px; line-height: 18px; }
.work-item-row-menu .row-action-delete:not(.is-disabled) { color: var(--el-color-danger); }
.work-item-row-menu .row-action-convert--open { background: var(--yp-bg-hover); }
.row-action-submenu-arrow { width: 14px; height: 14px; margin-left: auto; }
.work-item-parent-popover.el-popover { max-width: calc(100vw - 24px); padding: 16px; border-radius: 8px; box-sizing: border-box; }
.monday-table td.work-item-menu-column, .monday-table th.work-item-menu-column,
.monday-subitem-table td.work-item-menu-column, .monday-subitem-table th.work-item-menu-column { border: 0 !important; background: var(--yp-bg-surface) !important; }
.monday-table .work-item-menu-column > .cell, .monday-subitem-table .work-item-menu-column > .cell { overflow: visible; padding: 0; }
</style>
