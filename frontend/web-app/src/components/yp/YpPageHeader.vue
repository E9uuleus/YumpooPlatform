<script setup lang="ts">
defineProps<{
  title: string
  description?: string
  eyebrow?: string
}>()
</script>

<template>
  <header class="yp-page-header">
    <div
      v-if="$slots.leading"
      class="yp-page-header__leading"
    >
      <slot name="leading" />
    </div>
    <div class="yp-page-header__main">
      <div
        v-if="$slots.breadcrumbs"
        class="yp-page-header__breadcrumbs"
      >
        <slot name="breadcrumbs" />
      </div>
      <p
        v-if="eyebrow"
        class="yp-page-header__eyebrow"
      >
        {{ eyebrow }}
      </p>
      <h1>{{ title }}</h1>
      <p
        v-if="description"
        class="yp-page-header__description"
      >
        {{ description }}
      </p>
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

.yp-page-header__leading {
  display: grid;
  flex: none;
  place-items: center;
}

.yp-page-header__main {
  flex: 1 1 auto;
  min-width: 0;
}

.yp-page-header__breadcrumbs {
  margin-bottom: var(--yp-space-2);
}

.yp-page-header__eyebrow {
  margin: 0 0 var(--yp-space-1);
  color: var(--yp-link);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
  line-height: var(--yp-type-caption-line);
}

h1 {
  margin: 0;
  color: var(--yp-text-primary);
  font-size: var(--yp-type-page-title-size);
  font-family: var(--yp-font-heading);
  font-weight: 500;
  line-height: var(--yp-type-page-title-line);
}

.yp-page-header__description {
  max-width: 64em;
  margin: var(--yp-space-1) 0 0;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
  line-height: var(--yp-type-body-line);
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
