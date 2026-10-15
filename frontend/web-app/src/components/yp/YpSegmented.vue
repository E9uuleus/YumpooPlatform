<script setup lang="ts" generic="T extends string | number">
import { computed, nextTick, ref } from 'vue'
import { useSlidingIndicator } from './useSlidingIndicator'

export interface SegmentedItem<V extends string | number = string | number> {
  value: V
  label: string
  disabled?: boolean
  /** Shown on hover, e.g. why an option is unavailable. */
  title?: string
}

const props = defineProps<{
  options: readonly SegmentedItem<T>[]
  modelValue: T
  label: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: T]
  change: [value: T]
}>()

const group = ref<HTMLElement>()
const activeIndex = computed(() => props.options.findIndex(option => option.value === props.modelValue))
const { indicatorStyle, animated } = useSlidingIndicator(group, '.yp-segmented__item', () => activeIndex.value)

function select(value: T): void {
  if (value === props.modelValue || props.options.find(option => option.value === value)?.disabled) return
  emit('update:modelValue', value)
  emit('change', value)
}

async function move(event: KeyboardEvent, index: number): Promise<void> {
  const enabled = props.options.map((option, position) => option.disabled ? -1 : position).filter(position => position >= 0)
  const at = enabled.indexOf(index), last = enabled.length - 1
  if (at < 0) return
  const step = {
    ArrowRight: at === last ? 0 : at + 1,
    ArrowDown: at === last ? 0 : at + 1,
    ArrowLeft: at === 0 ? last : at - 1,
    ArrowUp: at === 0 ? last : at - 1,
    Home: 0,
    End: last,
  }[event.key]
  if (step === undefined) return
  const target = enabled[step]!
  event.preventDefault()
  select(props.options[target]!.value)
  await nextTick()
  group.value?.querySelectorAll<HTMLElement>('.yp-segmented__item')[target]?.focus()
}
</script>

<template>
  <div
    ref="group"
    class="yp-segmented"
    role="radiogroup"
    :aria-label="label"
  >
    <span
      class="yp-segmented__thumb"
      :class="{ 'yp-segmented__thumb--animated': animated }"
      :style="indicatorStyle"
      aria-hidden="true"
    />
    <button
      v-for="(option, index) in options"
      :key="option.value"
      class="yp-segmented__item"
      :class="{ 'is-on': option.value === modelValue }"
      type="button"
      role="radio"
      :aria-checked="option.value === modelValue"
      :disabled="option.disabled"
      :title="option.title"
      :tabindex="option.value === modelValue ? 0 : -1"
      @click="select(option.value)"
      @keydown="move($event, index)"
    >
      {{ option.label }}
    </button>
  </div>
</template>

<style scoped>
.yp-segmented {
  position: relative;
  display: inline-flex;
  flex: none;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-sm);
  background: var(--yp-bg-sunken);
}

.yp-segmented__thumb {
  position: absolute;
  top: 2px;
  bottom: 2px;
  left: 0;
  border-radius: var(--yp-radius-xs);
  background: var(--yp-bg-surface);
  box-shadow: 0 1px 2px color-mix(in srgb, var(--yp-text-primary) 14%, transparent);
  pointer-events: none;
}

.yp-segmented__thumb--animated {
  transition: transform var(--yp-motion-progress) var(--yp-ease-standard), width var(--yp-motion-progress) var(--yp-ease-standard);
}

.yp-segmented__item {
  position: relative;
  display: inline-flex;
  height: calc(var(--yp-control-height) - 6px);
  padding: 0 var(--yp-space-3);
  align-items: center;
  border: 0;
  border-radius: var(--yp-radius-xs);
  color: var(--yp-text-secondary);
  background: transparent;
  font: 500 13px / 1 var(--yp-font-family);
  white-space: nowrap;
  cursor: pointer;
  transition: color var(--yp-motion-fast) ease-out;
}

.yp-segmented__item:hover,
.yp-segmented__item.is-on {
  color: var(--yp-text-primary);
}

.yp-segmented__item:disabled {
  color: var(--yp-text-disabled);
  cursor: not-allowed;
}

.yp-segmented__item:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 1px;
}
</style>
