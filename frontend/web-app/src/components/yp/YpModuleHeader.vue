<script setup lang="ts">
import { computed } from 'vue'
import { ElIcon } from 'element-plus'
import { MODULES, type ModuleKey } from './moduleIdentity'

/** Module identity tile, module name and page title; meta sits after the title, actions on the right. */
const props = defineProps<{ module: ModuleKey; title: string }>()
const identity = computed(() => MODULES[props.module])
</script>

<template>
  <header
    class="yp-module-header"
    :style="{ '--module-color': identity.color }"
  >
    <span
      class="yp-module-header__tile"
      aria-hidden="true"
    ><el-icon><component :is="identity.icon" /></el-icon></span>
    <div class="yp-module-header__copy">
      <p class="yp-module-header__module">
        {{ identity.label }}
      </p>
      <h1>{{ title }}</h1>
    </div>
    <div
      v-if="$slots.meta"
      class="yp-module-header__meta"
    >
      <slot name="meta" />
    </div>
    <div
      v-if="$slots.actions"
      class="yp-module-header__actions"
    >
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.yp-module-header {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: var(--yp-space-3);
  min-height: 64px;
  padding: 10px var(--yp-space-6);
  box-sizing: border-box;
  background: var(--yp-bg-surface);
}

.yp-module-header__tile {
  display: grid;
  flex: none;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: var(--yp-radius-xl);
  background: color-mix(in srgb, var(--module-color) 12%, var(--yp-bg-surface));
  color: var(--module-color);
  font-size: 20px;
}

.yp-module-header__copy {
  min-width: 0;
}

.yp-module-header__module {
  margin: 0;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
  line-height: var(--yp-type-caption-line);
}

.yp-module-header h1 {
  margin: 0;
  overflow: hidden;
  color: var(--yp-text-primary);
  font: 600 24px / 32px var(--yp-font-heading);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.yp-module-header__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-3);
  min-width: 0;
  margin-left: var(--yp-space-3);
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
}

.yp-module-header__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--yp-space-2);
  margin-left: auto;
}

@media (max-width: 959.98px) {
  .yp-module-header {
    flex-wrap: wrap;
    padding: var(--yp-space-2) var(--yp-space-4);
  }

  .yp-module-header__meta {
    order: 3;
    flex-basis: 100%;
    margin-left: 52px;
  }
}
</style>
