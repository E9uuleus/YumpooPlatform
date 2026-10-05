<script setup lang="ts">
import '../workItemColumnHeader.css'
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon, ElInput, ElMessage, ElTooltip } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import WorkItemActionIcon from '../WorkItemActionIcon.vue'
import { problemMessage } from '../../../api/problems'
import { connectColumnNameError } from './connectColumnKeys'
import { toConnectProblem } from './connectProblems'

const props = defineProps<{ label: string; kind: 'connect' | 'reverse' | 'draft'; canManage: boolean; canDelete: boolean;
  collapsible?: boolean | undefined
  columnKey?: string | undefined; width?: number | undefined; minWidth?: number | undefined; reverseHint?: string | undefined
  takenNames?: string[] | undefined; rename?: ((name: string) => Promise<unknown>) | undefined }>()
const emit = defineEmits<{ edit: []; hide: []; delete: []; collapse: []; resize: [width: number, save?: boolean] }>()
const editing = ref(false), name = ref(''), saving = ref(false), input = ref<InstanceType<typeof ElInput>>()
const menuOpen = ref(false)
const canRename = computed(() => props.kind === 'connect' && props.canManage && Boolean(props.rename))
const nameError = computed(() => editing.value ? connectColumnNameError(name.value, (props.takenNames ?? []).filter(value => value !== props.label)) : '')
function command(action: string) {
  if (action === 'hide') emit('hide')
  else if (action === 'edit') emit('edit')
  else if (action === 'rename') startRename()
  else if (action === 'delete') emit('delete')
  else if (action === 'collapse') emit('collapse')
}
function startRename() {
  if (!canRename.value) return
  name.value = props.label; editing.value = true
  void nextTick(() => { input.value?.focus(); input.value?.select() })
}
async function commitRename() {
  if (!editing.value || saving.value) return
  if (name.value.trim() === props.label) { editing.value = false; return }
  if (nameError.value) return
  saving.value = true
  try { await props.rename?.(name.value.trim()); editing.value = false }
  catch (reason) { ElMessage.error(problemMessage(await toConnectProblem(reason))); void nextTick(() => input.value?.focus()) }
  finally { saving.value = false }
}
function blurRename() { if (nameError.value) editing.value = false; else void commitRename() }
let resize: { x: number; width: number; nextWidth: number; pointerId: number } | undefined
function start(event: PointerEvent) {
  if (resize) return
  event.preventDefault(); event.stopPropagation()
  const width = props.width ?? 200
  resize = { x: event.clientX, width, nextWidth: width, pointerId: event.pointerId }
  window.addEventListener('pointermove', move); window.addEventListener('pointerup', stop); window.addEventListener('pointercancel', cancel)
}
function move(event: PointerEvent) {
  if (resize?.pointerId !== event.pointerId) return
  resize.nextWidth = Math.max(props.minWidth ?? 140, Math.round(resize.width + event.clientX - resize.x))
  emit('resize', resize.nextWidth, false)
}
function stop(event: PointerEvent) {
  if (resize?.pointerId !== event.pointerId) return
  if (resize.nextWidth !== resize.width) emit('resize', resize.nextWidth, true)
  cleanup()
}
function cancel(event: PointerEvent) {
  if (resize?.pointerId !== event.pointerId) return
  emit('resize', resize.width, false)
  cleanup()
}
function cleanup() { resize = undefined; window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', stop); window.removeEventListener('pointercancel', cancel) }
onBeforeUnmount(cleanup)
</script>

<template>
  <div
    class="connect-column-header"
    :class="{
      'connect-column-header--draft': kind === 'draft',
      'connect-column-header--editing': editing,
      'work-item-column-header': collapsible && kind !== 'draft' && !editing,
      'work-item-column-header--menu-open': menuOpen,
    }"
    :data-connect-key="columnKey"
    @pointerdown.stop
  >
    <template v-if="editing">
      <el-tooltip
        :visible="Boolean(nameError)"
        :content="nameError"
        placement="top"
      >
        <el-input
          ref="input"
          v-model="name"
          class="connect-column-header__input"
          :class="{ 'is-invalid': nameError }"
          size="small"
          maxlength="40"
          :disabled="saving"
          aria-label="列名称"
          @keydown.enter.prevent="commitRename"
          @keydown.esc.stop.prevent="editing = false"
          @blur="blurRename"
        />
      </el-tooltip>
    </template>
    <template v-else>
      <el-tooltip
        v-if="kind === 'reverse'"
        :content="reverseHint ?? '双向连接'"
      >
        <svg
          class="connect-column-header__two-way"
          viewBox="0 0 16 16"
          width="14"
          height="14"
          aria-label="双向连接"
          role="img"
        >
          <path d="M2.5 5.5h10m-2.5-2.5 2.5 2.5-2.5 2.5M13.5 10.5h-10m2.5-2.5-2.5 2.5 2.5 2.5" />
        </svg>
      </el-tooltip>
      <span
        class="connect-column-header__label"
        :title="canRename ? `${label}（双击重命名）` : label"
        @dblclick.stop="startRename"
      >{{ label }}</span>
      <el-dropdown
        v-if="kind !== 'draft'"
        :class="{ 'work-item-column-header__menu': collapsible }"
        trigger="click"
        :placement="collapsible ? 'bottom-start' : 'bottom'"
        @visible-change="menuOpen = $event"
        @command="command"
      >
        <button
          type="button"
          class="connect-column-header__menu"
          :class="{ 'work-item-column-menu__trigger': collapsible }"
          :aria-label="`${label}列菜单`"
          :aria-expanded="menuOpen"
          aria-haspopup="menu"
          @click.stop
        >
          <work-item-action-icon
            v-if="collapsible"
            name="more"
          />
          <el-icon v-else>
            <more-filled />
          </el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-if="kind === 'connect' && canManage"
              command="edit"
            >
              连接设置
            </el-dropdown-item>
            <el-dropdown-item
              v-if="canRename"
              command="rename"
            >
              重命名
            </el-dropdown-item>
            <el-dropdown-item v-if="collapsible" command="collapse">折叠列</el-dropdown-item>
            <el-dropdown-item command="hide">
              隐藏列
            </el-dropdown-item>
            <el-dropdown-item
              v-if="kind === 'connect' && canDelete"
              command="delete"
              divided
            >
              删除列
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </template>
    <button
      v-if="width !== undefined && kind !== 'draft'"
      type="button"
      class="connect-column-header__resize"
      :aria-label="`调整${label}列宽`"
      @pointerdown="start"
      @click.stop
      @keydown.left.prevent="emit('resize', Math.max(minWidth ?? 140, width - 10))"
      @keydown.right.prevent="emit('resize', width + 10)"
    />
  </div>
</template>

<style scoped>
.connect-column-header { position: relative; display: flex; width: 100%; height: 36px; align-items: center; justify-content: center; gap: 5px; color: var(--yp-text-secondary); font-size: 13px; }
.connect-column-header.work-item-column-header { box-sizing: border-box; padding: 0 calc(6px + var(--work-item-header-actions-width)) 0 6px; }
.connect-column-header__label { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.connect-column-header--draft .connect-column-header__label { color: var(--yp-text-muted); font-style: italic; }
.connect-column-header__two-way { flex-shrink: 0; fill: none; stroke: var(--yp-text-muted); stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.connect-column-header__input { width: 100%; }
.connect-column-header__input.is-invalid :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px var(--yp-status-red) inset; }
.connect-column-header__menu:not(.work-item-column-menu__trigger) { display: flex; align-items: center; justify-content: center; width: 24px; height: 24px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-muted); cursor: pointer; }
.connect-column-header__menu:not(.work-item-column-menu__trigger):hover { background: var(--yp-bg-hover); color: var(--yp-text-primary); }
.connect-column-header__resize { position: absolute; right: -8px; top: 0; bottom: 0; width: 8px; padding: 0; border: 0; background: transparent; cursor: col-resize; touch-action: none; }
.connect-column-header__resize:hover, .connect-column-header__resize:focus-visible { border-right: 2px solid var(--yp-action-primary); }
</style>
