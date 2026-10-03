<script setup lang="ts">
import { ref } from 'vue'
import { ElPopover } from 'element-plus'
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
        <svg
          viewBox="0 0 20 20"
          fill="currentColor"
          width="18"
          height="18"
          aria-hidden="true"
        >
          <path
            d="M10 2.25C10.4142 2.25 10.75 2.58579 10.75 3V9.25H17C17.4142 9.25 17.75 9.58579 17.75 10C17.75 10.4142 17.4142 10.75 17 10.75H10.75V17C10.75 17.4142 10.4142 17.75 10 17.75C9.58579 17.75 9.25 17.4142 9.25 17V10.75H3C2.58579 10.75 2.25 10.4142 2.25 10C2.25 9.58579 2.58579 9.25 3 9.25H9.25V3C9.25 2.58579 9.58579 2.25 10 2.25Z"
            fill-rule="evenodd"
            clip-rule="evenodd"
          />
        </svg>
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
