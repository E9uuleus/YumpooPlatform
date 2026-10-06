<script setup lang="ts" generic="T extends string">
import { ElIcon } from 'element-plus'
import { nextTick, ref, type Component } from 'vue'

export interface ChoiceCardItem<V extends string = string> {
  value: V
  title: string
  description?: string
  icon?: Component
}

const props = defineProps<{
  options: readonly ChoiceCardItem<T>[]
  modelValue: T
  label: string
  disabled?: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [value: T] }>()
const group = ref<HTMLElement>()

function select(value: T): void {
  if (!props.disabled && value !== props.modelValue) emit('update:modelValue', value)
}

async function move(event: KeyboardEvent, index: number): Promise<void> {
  const last = props.options.length - 1
  const target = {
    ArrowDown: index === last ? 0 : index + 1,
    ArrowRight: index === last ? 0 : index + 1,
    ArrowUp: index === 0 ? last : index - 1,
    ArrowLeft: index === 0 ? last : index - 1,
    Home: 0,
    End: last,
  }[event.key]
  if (target === undefined) return
  event.preventDefault()
  select(props.options[target]!.value)
  await nextTick()
  group.value?.querySelectorAll<HTMLElement>('.yp-choice-card')[target]?.focus()
}
</script>

<template>
  <div
    ref="group"
    class="yp-choice-cards"
    role="radiogroup"
    :aria-label="label"
  >
    <button
      v-for="(option, index) in options"
      :key="option.value"
      type="button"
      class="yp-choice-card"
      :class="{ 'is-checked': option.value === modelValue }"
      role="radio"
      :aria-checked="option.value === modelValue"
      :tabindex="option.value === modelValue ? 0 : -1"
      :disabled="disabled"
      @click="select(option.value)"
      @keydown="move($event, index)"
    >
      <el-icon
        v-if="option.icon"
        class="yp-choice-card__icon"
        aria-hidden="true"
      >
        <component :is="option.icon" />
      </el-icon>
      <span class="yp-choice-card__copy">
        <strong>{{ option.title }}</strong>
        <span v-if="option.description">{{ option.description }}</span>
      </span>
      <span
        class="yp-choice-card__radio"
        aria-hidden="true"
      />
    </button>
  </div>
</template>

<style scoped>
.yp-choice-cards {
  display: grid;
  gap: var(--yp-space-3);
}

.yp-choice-card {
  display: flex;
  align-items: center;
  gap: var(--yp-space-4);
  width: 100%;
  min-height: 72px;
  padding: var(--yp-space-4) var(--yp-space-5);
  border: 1px solid var(--yp-border-default);
  border-radius: var(--yp-radius-md);
  color: var(--yp-text-primary);
  background: var(--yp-bg-surface);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--yp-motion-fast) var(--yp-ease-standard),
              background-color var(--yp-motion-fast) var(--yp-ease-standard);
}

.yp-choice-card:hover:not(:disabled) {
  background: var(--yp-bg-hover);
}

.yp-choice-card.is-checked,
.yp-choice-card.is-checked:hover:not(:disabled) {
  border-color: var(--yp-action-primary);
  background: var(--yp-bg-selected);
}

.yp-choice-card:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 2px;
}

.yp-choice-card:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.yp-choice-card__icon {
  flex: none;
  color: var(--yp-text-secondary);
  font-size: 20px;
}

.yp-choice-card__copy {
  display: grid;
  flex: 1;
  min-width: 0;
  gap: 2px;
}

.yp-choice-card__copy strong {
  font: 500 var(--yp-type-card-title-size) / var(--yp-type-card-title-line) var(--yp-font-family);
}

.yp-choice-card__copy span {
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
  line-height: var(--yp-type-body-line);
}

.yp-choice-card__radio {
  flex: none;
  width: 18px;
  height: 18px;
  box-sizing: border-box;
  border: 1.5px solid var(--yp-border-strong);
  border-radius: 50%;
  background: var(--yp-bg-surface);
  transition: border-color var(--yp-motion-fast) var(--yp-ease-standard),
              background-color var(--yp-motion-fast) var(--yp-ease-standard);
}

.yp-choice-card.is-checked .yp-choice-card__radio {
  border-color: var(--yp-action-primary);
  background: var(--yp-action-primary);
  box-shadow: inset 0 0 0 3px var(--yp-bg-surface);
}
</style>
