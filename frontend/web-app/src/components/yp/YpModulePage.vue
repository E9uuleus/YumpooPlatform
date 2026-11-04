<script setup lang="ts">
import YpModuleHeader from './YpModuleHeader.vue'
import type { ModuleKey } from './moduleIdentity'

/** Module page frame: the header stays while the body scrolls on its own. */
defineProps<{ module: ModuleKey; title: string }>()
</script>

<template>
  <section class="yp-module-page">
    <yp-module-header
      :module="module"
      :title="title"
    >
      <template
        v-if="$slots.meta"
        #meta
      >
        <slot name="meta" />
      </template>
      <template
        v-if="$slots.actions"
        #actions
      >
        <slot name="actions" />
      </template>
    </yp-module-header>
    <div class="yp-module-page__body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.yp-module-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.yp-module-page__body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding: var(--yp-space-5) var(--yp-space-6) var(--yp-space-8);
  border-top: 1px solid var(--yp-border-subtle);
}

@media (max-width: 959.98px) {
  .yp-module-page__body {
    padding: var(--yp-space-4);
  }
}
</style>
