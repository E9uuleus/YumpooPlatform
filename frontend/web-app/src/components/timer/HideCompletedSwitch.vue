<script setup lang="ts">
import { ref } from 'vue'
import { hideCompleted, setHideCompleted } from '../../composables/useHideCompleted'

/** toolbar 为工作项表格工具栏的写法（文案“隐没已完成”，不显示数量）；默认是选择列表旁的紧凑开关。 */
withDefaults(defineProps<{ hiddenCount?: number; toolbar?: boolean; disabled?: boolean }>(), { hiddenCount: 0, toolbar: false, disabled: false })
const saving = ref(false)

async function toggle() {
  saving.value = true
  try { await setHideCompleted(!hideCompleted.value) } finally { saving.value = false }
}
</script>

<template>
  <span
    class="hide-completed"
    :class="{ 'hide-completed--toolbar': toolbar }"
  >
    <button
      type="button"
      role="switch"
      class="hide-completed__button"
      :class="{ active: hideCompleted }"
      :aria-checked="hideCompleted"
      :disabled="disabled || saving"
      @click="toggle"
    >
      <span
        class="hide-completed__track"
        aria-hidden="true"
      />
      <span>{{ toolbar ? '隐没已完成' : '隐藏已完成' }}</span>
    </button>
    <small
      v-if="!toolbar && hideCompleted && hiddenCount"
      class="hide-completed__count"
    >已隐藏 {{ hiddenCount }} 项</small>
  </span>
</template>

<style scoped>
.hide-completed{display:inline-flex;align-items:center;gap:8px;min-width:0}
.hide-completed__button{display:inline-flex;flex:none;align-items:center;gap:8px;padding:0;border:0;background:transparent;color:var(--yp-text-primary);font:13px var(--yp-font-family);white-space:nowrap;cursor:pointer}
.hide-completed__button:disabled{color:var(--yp-text-disabled);cursor:not-allowed}
.hide-completed__track{position:relative;flex:none;width:30px;height:17px;border-radius:var(--yp-radius-pill);background:var(--yp-border-strong);transition:background var(--yp-motion-popover) var(--yp-ease-standard)}
.hide-completed__track::after{content:'';position:absolute;top:2px;left:2px;width:13px;height:13px;border-radius:50%;background:var(--yp-priority-foreground);transition:transform var(--yp-motion-popover) var(--yp-ease-standard)}
.hide-completed__button.active .hide-completed__track{background:var(--yp-action-primary)}
.hide-completed__button.active .hide-completed__track::after{transform:translateX(13px)}
.hide-completed__count{overflow:hidden;color:var(--yp-text-muted);font-size:12px;white-space:nowrap;text-overflow:ellipsis}
.hide-completed--toolbar .hide-completed__button{height:36px;padding:0 var(--yp-space-3);border-radius:var(--yp-radius-sm);font:inherit;transition:background var(--yp-motion-fast) var(--yp-ease-standard),color var(--yp-motion-fast) var(--yp-ease-standard)}
.hide-completed--toolbar .hide-completed__button:hover:not(:disabled),.hide-completed--toolbar .hide-completed__button.active{background:var(--yp-bg-hover);color:var(--yp-action-primary)}
.hide-completed--toolbar .hide-completed__track{width:28px;height:16px}
.hide-completed--toolbar .hide-completed__track::after{width:12px;height:12px}
.hide-completed--toolbar .hide-completed__button.active .hide-completed__track::after{transform:translateX(12px)}
</style>
