<script setup lang="ts">
import { ref } from 'vue'
import { ElIcon, ElPopover } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import ConnectColumnAddPopover from './ConnectColumnAddPopover.vue'

defineProps<{ canManage: boolean; hiddenColumns: { key: string; label: string }[] }>()
const emit = defineEmits<{ addConnectColumn: []; showColumn: [key: string] }>()
const open = ref(false)
function add() { open.value = false; emit('addConnectColumn') }
function show(key: string) { open.value = false; emit('showColumn', key) }
</script>

<template>
  <el-popover
    v-model:visible="open"
    trigger="click"
    placement="bottom-end"
    :width="440"
    popper-class="connect-column-center-popper"
  >
    <template #reference>
      <button
        type="button"
        class="connect-column-add-button"
        aria-label="添加列"
        title="添加列"
        @click.stop
        @keydown.esc="open = false"
      >
        <el-icon><plus /></el-icon>
      </button>
    </template>
    <connect-column-add-popover
      v-if="open"
      :can-manage="canManage"
      :hidden-columns="hiddenColumns"
      @add-connect-column="add"
      @show-column="show"
    />
  </el-popover>
</template>

<style scoped>
.connect-column-add-button { display: inline-flex; width: 32px; height: 32px; align-items: center; justify-content: center; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-secondary); cursor: pointer; font-size: 18px; }
.connect-column-add-button:hover { background: var(--yp-bg-hover); color: var(--yp-text-primary); }
.connect-column-add-button:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: -2px; }
</style>

<style>
.connect-column-center-popper { max-width: calc(100vw - 32px); box-sizing: border-box; }
</style>
