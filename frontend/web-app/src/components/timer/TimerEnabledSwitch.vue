<script setup lang="ts">
import { ref } from 'vue'
import { ElMessageBox, ElSwitch } from 'element-plus'
import { useTimeTracker } from '../../composables/useTimeTracker'

const tracker = useTimeTracker()
const saving = ref(false)

async function setTimer(enabled: string | number | boolean) {
  const running = tracker.running.value.length
  if (!enabled && running) {
    try {
      await ElMessageBox.confirm(`正在计时的 ${running} 项会立即停止，已计时长保留。之后只需在“我的一天”里按时段填写工时。`, '关闭计时器', {
        confirmButtonText: '停止并关闭', cancelButtonText: '取消', type: 'warning' })
    } catch { return }
  }
  saving.value = true
  try { await tracker.setPreferences({ ...tracker.preferences.value, timerEnabled: !!enabled }) } finally { saving.value = false }
}
</script>

<template>
  <el-switch
    :model-value="tracker.preferences.value.timerEnabled"
    :loading="saving"
    aria-label="使用计时器"
    @change="setTimer"
  />
</template>
