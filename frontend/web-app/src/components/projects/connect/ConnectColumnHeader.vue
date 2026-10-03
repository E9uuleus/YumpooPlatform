<script setup lang="ts">
import { onBeforeUnmount } from 'vue'
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon, ElTooltip } from 'element-plus'
import { InfoFilled, MoreFilled } from '@element-plus/icons-vue'

const props = defineProps<{ label: string; kind: 'connect' | 'incoming'; canManage: boolean; canDelete: boolean;
  columnKey?: string | undefined; width?: number | undefined; minWidth?: number | undefined }>()
const emit = defineEmits<{ edit: []; hide: []; delete: []; resize: [width: number] }>()
function command(action: string) { if (action === 'hide') emit('hide'); else if (action === 'edit') emit('edit'); else if (action === 'delete') emit('delete') }
let resize: { x: number; width: number; pointerId: number } | undefined
function start(event: PointerEvent) {
  event.preventDefault(); event.stopPropagation()
  resize = { x: event.clientX, width: props.width ?? 200, pointerId: event.pointerId }
  window.addEventListener('pointermove', move); window.addEventListener('pointerup', stop); window.addEventListener('pointercancel', stop)
}
function move(event: PointerEvent) {
  if (resize?.pointerId === event.pointerId) emit('resize', Math.max(props.minWidth ?? 140, Math.round(resize.width + event.clientX - resize.x)))
}
function stop() { resize = undefined; window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', stop); window.removeEventListener('pointercancel', stop) }
onBeforeUnmount(stop)
</script>

<template>
  <div
    class="connect-column-header"
    :data-connect-key="columnKey"
    @pointerdown.stop
  >
    <span
      class="connect-column-header__label"
      :title="label"
    >{{ label }}</span>
    <el-tooltip
      v-if="kind === 'incoming'"
      content="其他项目通过连接列关联到这里的工作项"
    >
      <el-icon class="connect-column-header__info">
        <info-filled />
      </el-icon>
    </el-tooltip>
    <el-dropdown
      trigger="click"
      @command="command"
    >
      <button
        type="button"
        class="connect-column-header__menu"
        :aria-label="`${label}列菜单`"
        @click.stop
      >
        <el-icon><more-filled /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-if="kind === 'connect' && canManage"
            command="edit"
          >
            设置
          </el-dropdown-item>
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
    <button
      v-if="width !== undefined"
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
.connect-column-header__label { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.connect-column-header__info { flex-shrink: 0; color: var(--yp-text-muted); font-size: 14px; }
.connect-column-header__menu { display: flex; align-items: center; justify-content: center; width: 24px; height: 24px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-muted); cursor: pointer; }
.connect-column-header__menu:hover { background: var(--yp-bg-hover); color: var(--yp-text-primary); }
.connect-column-header__resize { position: absolute; right: -8px; top: 0; bottom: 0; width: 8px; padding: 0; border: 0; background: transparent; cursor: col-resize; touch-action: none; }
.connect-column-header__resize:hover, .connect-column-header__resize:focus-visible { border-right: 2px solid var(--yp-action-primary); }
</style>
