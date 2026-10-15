<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElPopover, ElTimeSelect, ElTooltip } from 'element-plus'
import { Setting } from '@element-plus/icons-vue'
import { useTimeTracker } from '../../composables/useTimeTracker'
import TimerEnabledSwitch from '../timer/TimerEnabledSwitch.vue'

const tracker = useTimeTracker()
const preferences = tracker.preferences
const saving = tracker.busy
const clock = (minute: number) => minute >= 1440 ? '24:00' : `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`
const minute = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3, 5))
const start = computed(() => clock(preferences.value.workdayStartMinute))
const end = computed(() => clock(preferences.value.workdayEndMinute))

async function save(patch: Partial<typeof preferences.value>) {
  const next = { ...preferences.value, ...patch }
  if (next.workdayEndMinute <= next.workdayStartMinute) return
  await tracker.setPreferences(next)
}
</script>

<template>
  <el-popover
    trigger="click"
    placement="bottom-end"
    :width="300"
    popper-class="time-preferences-popover"
  >
    <template #reference>
      <span class="time-preferences__trigger">
        <el-tooltip content="工时设置">
          <el-button
            text
            :icon="Setting"
            aria-label="工时设置"
          />
        </el-tooltip>
      </span>
    </template>
    <div class="time-preferences">
      <div class="time-preferences__row">
        <span>
          <strong>使用计时器</strong>
          <small>关闭后隐藏所有计时入口，只按时段手动填写。</small>
        </span>
        <TimerEnabledSwitch />
      </div>
      <div class="time-preferences__row is-stacked">
        <span>
          <strong>上班时间</strong>
          <small>用于提示空白时段，以及按时长排入的起点。</small>
        </span>
        <div class="time-preferences__times">
          <el-time-select
            :model-value="start"
            start="00:00"
            end="23:30"
            step="00:30"
            :clearable="false"
            :disabled="saving"
            aria-label="上班时间"
            @change="value => value && save({ workdayStartMinute: minute(String(value)) })"
          />
          <span aria-hidden="true">–</span>
          <el-time-select
            :model-value="end"
            start="00:30"
            end="24:00"
            step="00:30"
            :min-time="start"
            :clearable="false"
            :disabled="saving"
            aria-label="下班时间"
            @change="value => value && save({ workdayEndMinute: minute(String(value)) })"
          />
        </div>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.time-preferences__trigger{display:inline-flex}
.time-preferences{display:grid;gap:14px}
.time-preferences__row{display:flex;align-items:center;gap:12px}
.time-preferences__row.is-stacked{flex-direction:column;align-items:stretch;gap:8px}
.time-preferences__row>span{display:grid;flex:1;gap:2px}
.time-preferences__row strong{color:var(--yp-text-primary);font-size:13px;font-weight:600}
.time-preferences__row small{color:var(--yp-text-muted);font-size:12px;line-height:18px}
.time-preferences__times{display:flex;align-items:center;gap:6px;color:var(--yp-text-secondary)}
.time-preferences__times :deep(.el-select){flex:1}
</style>
