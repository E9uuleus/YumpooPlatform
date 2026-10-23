<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElButton } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import { TimeSessionFillMode } from '@yumpoo/api-client'
import type { SubjectOption } from '../../timer/timeCategories'
import ItemPicker from './ItemPicker.vue'
import OverlapModeControl from './OverlapModeControl.vue'
import TimeField from './TimeField.vue'
import { defaultMode, durationLabel, MIN_BLOCK_MS, overlapEffect, type DayBlock, type Range } from './dayModel'

const props = defineProps<{ range: Range; blocks: DayBlock[]; bounds: Range; limit: number; date: string; timezone: string; busy: boolean }>()
const emit = defineEmits<{
  submit: [mode: TimeSessionFillMode, items: SubjectOption[], range: Range]
  'update:range': [range: Range]
  close: []
}>()

const selected = ref<SubjectOption[]>([])
const touched = ref(false)
const effect = computed(() => overlapEffect(props.range, selected.value.map(item => item.key), props.blocks, props.limit))
const mode = ref(defaultMode(effect.value))
watch(() => effect.value.fullyCovered, () => { if (!touched.value) mode.value = defaultMode(effect.value) })
const problem = computed(() => {
  if (props.range.end <= props.range.start) return '结束时间要晚于开始时间'
  if (props.range.end - props.range.start < MIN_BLOCK_MS) return '至少记录 15 分钟'
  if (props.range.end > props.limit) return '不能记录未来的时间'
  if (props.range.start < props.bounds.start) return '开始时间不能早于当天'
  return ''
})

function setMode(value: TimeSessionFillMode) { touched.value = true; mode.value = value }
function setStart(start: number) { emit('update:range', { start, end: props.range.end }) }
function setEnd(end: number) { emit('update:range', { start: props.range.start, end }) }
function submit(items: SubjectOption[]) {
  if (problem.value || props.busy || !items.length) return
  emit('submit', effect.value.overlapping.length ? mode.value : TimeSessionFillMode.Gaps, items, props.range)
}
</script>

<template>
  <div
    class="entry-composer"
    role="dialog"
    aria-label="补录这段时间"
  >
    <header class="entry-composer__head">
      <TimeField
        :model-value="range.start"
        :date="date"
        :timezone="timezone"
        :day-start="bounds.start"
        :day-end="bounds.end"
        label="开始时间"
        @update:model-value="setStart"
      />
      <span aria-hidden="true">–</span>
      <TimeField
        :model-value="range.end"
        :date="date"
        :timezone="timezone"
        :day-start="bounds.start"
        :day-end="bounds.end"
        label="结束时间"
        @update:model-value="setEnd"
      />
      <span class="entry-composer__duration">{{ range.end > range.start ? durationLabel(range.end - range.start) : '' }}</span>
      <el-button
        text
        circle
        size="small"
        :icon="Close"
        aria-label="关闭"
        class="entry-composer__close"
        @click="emit('close')"
      />
    </header>
    <p
      v-if="problem"
      class="entry-composer__problem"
      role="alert"
    >
      {{ problem }}
    </p>
    <ItemPicker
      v-model:selected="selected"
      multiple
      placeholder="这段时间在做什么？搜索工作项"
      @pick="submit([$event])"
      @escape="emit('close')"
    />
    <OverlapModeControl
      v-if="effect.overlapping.length"
      :model-value="mode"
      :effect="effect"
      :range="range"
      :item-count="Math.max(1, selected.length)"
      @update:model-value="setMode"
    />
    <footer class="entry-composer__foot">
      <template v-if="selected.length">
        <span class="entry-composer__chosen">已选 {{ selected.length }} 项，将并行记录</span>
        <el-button
          text
          size="small"
          @click="selected = []"
        >
          清空
        </el-button>
        <el-button
          type="primary"
          size="small"
          :loading="busy"
          :disabled="!!problem"
          @click="submit(selected)"
        >
          添加 {{ selected.length }} 项
        </el-button>
      </template>
      <span
        v-else
        class="entry-composer__hint"
      >单击工作项即保存 · ⌘/Ctrl+单击可多选并行</span>
    </footer>
  </div>
</template>

<style scoped>
.entry-composer{display:grid;gap:10px}
.entry-composer__head{display:flex;align-items:center;gap:6px;color:var(--yp-text-secondary)}
.entry-composer__duration{color:var(--yp-text-primary);font:600 13px var(--yp-font-mono);font-variant-numeric:tabular-nums}
.entry-composer__close{margin-left:auto}
.entry-composer__problem{margin:0;color:var(--yp-status-red);font-size:12px}
.entry-composer__foot{display:flex;align-items:center;gap:6px;min-height:28px}
.entry-composer__foot .el-button+.el-button{margin-left:0}
.entry-composer__chosen{flex:1;color:var(--yp-text-secondary);font-size:12px}
.entry-composer__hint{color:var(--yp-text-muted);font-size:12px}
</style>
