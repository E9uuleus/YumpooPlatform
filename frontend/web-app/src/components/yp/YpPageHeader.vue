<script setup lang="ts">
defineProps<{
  title: string
  description?: string
  eyebrow?: string
}>()
</script>

<template>
  <header class="yp-page-header">
    <div class="yp-page-header__main">
      <div
        v-if="$slots.breadcrumbs"
        class="yp-page-header__breadcrumbs"
      >
        <slot name="breadcrumbs" />
      </div>
      <h1>{{ title }}</h1>
      <div
        v-if="$slots.meta"
        class="yp-page-header__meta"
      >
        <slot name="meta" />
      </div>
    </div>
    <div
      v-if="$slots.actions"
      class="yp-page-header__actions"
    >
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.yp-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--yp-space-6);
  margin-bottom: var(--yp-space-5);
  padding-bottom: var(--yp-space-5);
  border-bottom: 1px solid var(--yp-border-subtle);
}

.yp-page-header__main {
  min-width: 0;
}

.yp-page-header__breadcrumbs {
  margin-bottom: var(--yp-space-2);
}

h1 {
  margin: 0;
  color: var(--yp-text-primary);
  font-size: var(--yp-type-page-title-size);
  font-family: var(--yp-font-heading);
  font-weight: 500;
  line-height: var(--yp-type-page-title-line);
}

.yp-page-header__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-3);
  margin-top: var(--yp-space-2);
}

.yp-page-header__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--yp-space-2);
}

@media (max-width: 640px) {
  .yp-page-header {
    flex-direction: column;
  }

  .yp-page-header__actions {
    width: 100%;
  }
}
</style>
