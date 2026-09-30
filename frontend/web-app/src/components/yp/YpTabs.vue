<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useSlidingIndicator } from './useSlidingIndicator'

export interface TabItem {
  value: string
  label: string
  id?: string
  controls?: string
  count?: number | null
}

const props = defineProps<{
  items: readonly TabItem[]
  modelValue: string
  label: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const list = ref<HTMLElement>()
const activeIndex = computed(() => props.items.findIndex(item => item.value === props.modelValue))
const { indicatorStyle, animated } = useSlidingIndicator(list, '.yp-tabs__tab', () => activeIndex.value)

function select(value: string): void {
  if (value !== props.modelValue) emit('update:modelValue', value)
}

async function move(event: KeyboardEvent, index: number): Promise<void> {
  const last = props.items.length - 1
  const target = {
    ArrowRight: index === last ? 0 : index + 1,
    ArrowLeft: index === 0 ? last : index - 1,
    Home: 0,
    End: last,
  }[event.key]
  if (target === undefined) return
  event.preventDefault()
  select(props.items[target]!.value)
  await nextTick()
  list.value?.querySelectorAll<HTMLElement>('.yp-tabs__tab')[target]?.focus()
}
</script>

<template>
  <div
    ref="list"
    class="yp-tabs"
    role="tablist"
    :aria-label="label"
  >
    <button
      v-for="(item, index) in items"
      :id="item.id"
      :key="item.value"
      class="yp-tabs__tab"
      :class="{ selected: item.value === modelValue }"
      type="button"
      role="tab"
      :aria-selected="item.value === modelValue"
      :aria-controls="item.controls"
      :tabindex="item.value === modelValue ? 0 : -1"
      @click="select(item.value)"
      @keydown="move($event, index)"
    >
      <slot :name="`icon-${item.value}`" />
      <span>{{ item.label }}</span>
      <span
        v-if="item.count != null"
        class="yp-tabs__count"
      >{{ item.count }}</span>
    </button>
    <span
      class="yp-tabs__ink"
      :class="{ 'yp-tabs__ink--animated': animated }"
      :style="indicatorStyle"
      aria-hidden="true"
    />
  </div>
</template>

<style scoped>
.yp-tabs {
  position: relative;
  display: flex;
  min-height: 44px;
  align-items: stretch;
  gap: var(--yp-space-2);
  border-bottom: 1px solid var(--yp-border-subtle);
}

.yp-tabs__tab {
  display: inline-flex;
  flex: none;
  min-width: 96px;
  padding: 0 var(--yp-space-4);
  align-items: center;
  justify-content: center;
  gap: var(--yp-space-2);
  border: 0;
  border-radius: var(--yp-radius-xs) var(--yp-radius-xs) 0 0;
  color: var(--yp-text-secondary);
  background: transparent;
  font: inherit;
  font-size: var(--yp-type-body-size);
  font-weight: 500;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard), background var(--yp-motion-fast) var(--yp-ease-standard);
}

.yp-tabs__tab:hover {
  color: var(--yp-text-primary);
  background: var(--yp-bg-hover);
}

.yp-tabs__tab.selected {
  color: var(--yp-text-primary);
}

.yp-tabs__tab:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: -2px;
}

.yp-tabs__tab :deep(svg) {
  width: 18px;
  height: 18px;
}

.yp-tabs__count {
  min-width: 20px;
  padding: 0 6px;
  border-radius: var(--yp-radius-pill);
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
  font-size: var(--yp-type-caption-size);
  font-variant-numeric: tabular-nums;
  line-height: 18px;
}

.yp-tabs__ink {
  position: absolute;
  bottom: -1px;
  left: 0;
  height: 2.5px;
  border-radius: var(--yp-radius-pill) var(--yp-radius-pill) 0 0;
  background: var(--yp-action-primary);
  pointer-events: none;
}

.yp-tabs__ink--animated {
  transition: transform var(--yp-motion-progress) var(--yp-ease-standard), width var(--yp-motion-progress) var(--yp-ease-standard);
}
</style>
