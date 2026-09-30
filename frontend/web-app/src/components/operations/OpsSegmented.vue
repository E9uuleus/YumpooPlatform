<script setup lang="ts">
type Value = string | number

export interface SegmentedOption {
  value: Value
  label: string
  count?: string | number | null
  tone?: 'blue' | 'green' | 'yellow' | 'red' | 'gray' | 'purple' | 'teal'
}

const props = defineProps<{
  options: readonly SegmentedOption[]
  selected: Value | readonly Value[] | null | undefined
  groupLabel: string
}>()

const emit = defineEmits<{ select: [value: Value] }>()

function pressed(value: Value): boolean {
  return Array.isArray(props.selected) ? props.selected.includes(value) : props.selected === value
}
</script>

<template>
  <div
    class="ops-segmented"
    role="group"
    :aria-label="groupLabel"
  >
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      class="ops-segmented__item"
      :class="[option.tone ? `ops-tone-${option.tone}` : '', { 'is-on': pressed(option.value) }]"
      :aria-pressed="pressed(option.value)"
      @click="emit('select', option.value)"
    >
      {{ option.label }} <b
        v-if="option.count != null"
        class="ops-segmented__count"
      >{{ option.count }}</b>
    </button>
  </div>
</template>

<style scoped>
.ops-segmented {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-sm);
  background: var(--yp-bg-sunken);
}

.ops-segmented__item {
  display: inline-flex;
  align-items: center;
  gap: var(--yp-space-1);
  height: 26px;
  padding: 0 var(--yp-space-3);
  border: 1px solid transparent;
  border-radius: var(--yp-radius-xs);
  color: var(--yp-text-secondary);
  background: transparent;
  font: 500 var(--yp-type-caption-size) / 1 var(--yp-font-family);
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--yp-motion-fast) ease-out, color var(--yp-motion-fast) ease-out;
}

.ops-segmented__item:hover {
  color: var(--yp-text-primary);
}

.ops-segmented__item.is-on {
  color: var(--yp-text-primary);
  background: var(--yp-bg-surface);
  box-shadow: 0 1px 2px color-mix(in srgb, var(--yp-text-primary) 14%, transparent);
  font-weight: 600;
}

.ops-segmented__item[class*='ops-tone-'].is-on {
  border-color: color-mix(in srgb, var(--ops-tone) 50%, var(--yp-border-default));
  background: color-mix(in srgb, var(--ops-tone) 12%, var(--yp-bg-surface));
}

.ops-segmented__count {
  color: var(--yp-text-muted);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.is-on .ops-segmented__count {
  color: var(--yp-text-primary);
}
</style>
