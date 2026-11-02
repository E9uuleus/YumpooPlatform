<script setup lang="ts">
import { Check } from '@element-plus/icons-vue'
import { ElIcon } from 'element-plus'

defineProps<{
  steps: readonly string[]
  /** 当前步骤的下标，从 0 开始；之前的步骤显示对勾。 */
  current: number
}>()
</script>

<template>
  <ol
    class="import-steps"
    aria-label="导入步骤"
  >
    <li
      v-for="(label, index) in steps"
      :key="label"
      class="import-steps__item"
      :class="{ 'is-done': index < current, 'is-current': index === current }"
      :aria-current="index === current ? 'step' : undefined"
    >
      <span
        class="import-steps__mark"
        aria-hidden="true"
      >
        <el-icon v-if="index < current"><check /></el-icon>
        <template v-else>{{ index + 1 }}</template>
      </span>
      <span class="import-steps__label">{{ label }}</span>
      <span class="sr-only">{{ index < current ? '，已完成' : index === current ? '，进行中' : '' }}</span>
    </li>
  </ol>
</template>

<style scoped>
.import-steps {
  display: flex;
  margin: 0;
  padding: 0;
  align-items: center;
  gap: var(--yp-space-3);
  list-style: none;
}

.import-steps__item {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--yp-space-2);
  color: var(--yp-text-muted);
  font: 500 13px / 1.4 var(--yp-font-family);
}

.import-steps__item + .import-steps__item::before {
  width: 28px;
  height: 1px;
  flex: none;
  margin-right: var(--yp-space-1);
  background: var(--yp-border-default);
  content: '';
}

.import-steps__mark {
  display: inline-flex;
  width: 22px;
  height: 22px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--yp-border-strong);
  border-radius: var(--yp-radius-pill);
  font-size: 12px;
}

.import-steps__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-steps__item.is-current {
  color: var(--yp-text-primary);
}

.import-steps__item.is-current .import-steps__mark {
  border-color: var(--yp-action-primary);
  color: var(--yp-on-solid);
  background: var(--yp-action-primary);
}

.import-steps__item.is-done {
  color: var(--yp-text-secondary);
}

.import-steps__item.is-done .import-steps__mark {
  border-color: var(--yp-action-primary);
  color: var(--yp-action-primary);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>
