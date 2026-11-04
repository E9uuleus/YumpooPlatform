<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { ElButton } from 'element-plus'

defineProps<{ value: string | null; busy: boolean }>()
const emit = defineEmits<{ change: [accelerator: string | null] }>()
const recording = ref(false)
const problem = ref('')

const NAMED: Record<string, string> = { Space: 'Space', ArrowUp: 'Up', ArrowDown: 'Down', ArrowLeft: 'Left', ArrowRight: 'Right',
  Home: 'Home', End: 'End', PageUp: 'PageUp', PageDown: 'PageDown', Insert: 'Insert', Delete: 'Delete' }

/** The key part of an Electron accelerator, taken from the physical key so Shift or the keyboard layout never change it. */
function keyName(code: string): string | undefined {
  if (/^Key[A-Z]$/.test(code)) return code.slice(3)
  if (/^Digit\d$/.test(code)) return code.slice(5)
  if (/^F(?:[1-9]|1\d|2[0-4])$/.test(code)) return code
  return NAMED[code]
}

function keydown(event: KeyboardEvent) {
  event.preventDefault()
  event.stopPropagation()
  if (event.key === 'Escape') { stop(); return }
  const key = keyName(event.code)
  if (!key) return
  if (!event.ctrlKey && !event.altKey && !event.metaKey) { problem.value = '至少包含 Ctrl、Alt 或 Win 键'; return }
  const parts = [event.ctrlKey && 'Ctrl', event.altKey && 'Alt', event.shiftKey && 'Shift', event.metaKey && 'Super', key].filter(Boolean)
  stop()
  emit('change', parts.join('+'))
}
function start() {
  problem.value = ''
  recording.value = true
  window.addEventListener('keydown', keydown, true)
}
function stop() {
  recording.value = false
  window.removeEventListener('keydown', keydown, true)
}
onBeforeUnmount(stop)
</script>

<template>
  <div class="shortcut">
    <span
      class="shortcut__keys"
      :class="{ 'is-recording': recording }"
      aria-live="polite"
    >{{ recording ? (problem || '按下组合键…') : value ? value.split('+').map(part => part === 'Super' ? 'Win' : part).join(' + ') : '未设置' }}</span>
    <el-button
      size="small"
      :disabled="busy"
      text
      @click="recording ? stop() : start()"
    >
      {{ recording ? '取消' : '录制' }}
    </el-button>
    <el-button
      size="small"
      :disabled="busy || !value"
      text
      @click="emit('change', null)"
    >
      清除
    </el-button>
  </div>
</template>

<style scoped>
.shortcut{display:flex;align-items:center;gap:8px;flex-shrink:0}
.shortcut .el-button+.el-button{margin-left:0}
.shortcut__keys{display:inline-flex;align-items:center;justify-content:center;min-width:96px;height:28px;padding:0 10px;border-radius:7px;background:var(--yp-bg-sunken);font:12px var(--yp-font-mono);outline:1px solid transparent;transition:outline-color .2s}
.shortcut__keys.is-recording{outline-color:var(--yp-action-primary);color:var(--yp-action-primary)}
</style>
