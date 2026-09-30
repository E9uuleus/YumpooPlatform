<script setup lang="ts">
defineProps<{
  title: string
  description?: string
  eyebrow?: string
}>()
</script>

<template>
  <div class="yp-result-page">
    <div
      v-if="$slots.scene"
      class="yp-result-page__scene"
      aria-hidden="true"
    >
      <slot name="scene" />
    </div>
    <div class="yp-result-page__content">
      <p
        v-if="eyebrow"
        class="yp-result-page__eyebrow"
      >
        {{ eyebrow }}
      </p>
      <h1>{{ title }}</h1>
      <p
        v-if="description"
        class="yp-result-page__description"
      >
        {{ description }}
      </p>
      <div
        v-if="$slots.extra"
        class="yp-result-page__extra"
      >
        <slot name="extra" />
      </div>
      <div
        v-if="$slots.actions"
        class="yp-result-page__actions"
      >
        <slot name="actions" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.yp-result-page {
  display: grid;
  width: min(760px, 100%);
  grid-template-columns: 280px minmax(0, 1fr);
  align-items: center;
  gap: var(--yp-space-10);
  animation: yp-result-enter var(--yp-motion-overlay) var(--yp-ease-standard) both;
}

.yp-result-page:not(:has(.yp-result-page__scene)) {
  grid-template-columns: minmax(0, 1fr);
  text-align: center;
}

.yp-result-page__scene {
  width: 280px;
  height: 240px;
}

.yp-result-page__content {
  min-width: 0;
}

.yp-result-page__eyebrow {
  margin: 0 0 var(--yp-space-1);
  color: var(--yp-link);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
  line-height: var(--yp-type-caption-line);
  font-variant-numeric: tabular-nums;
}

h1 {
  margin: 0;
  color: var(--yp-text-primary);
  font: 500 var(--yp-type-page-title-size) / var(--yp-type-page-title-line) var(--yp-font-heading);
}

.yp-result-page__description {
  max-width: 32em;
  margin: var(--yp-space-2) 0 0;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
  line-height: var(--yp-type-body-line);
  text-wrap: pretty;
}

.yp-result-page__extra {
  margin-top: var(--yp-space-5);
}

.yp-result-page__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--yp-space-2);
  margin-top: var(--yp-space-6);
}

.yp-result-page:not(:has(.yp-result-page__scene)) .yp-result-page__actions {
  justify-content: center;
}

@keyframes yp-result-enter {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
}

@media (max-width: 720px) {
  .yp-result-page {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--yp-space-5);
    justify-items: center;
    text-align: center;
  }

  .yp-result-page__scene {
    width: 220px;
    height: 188px;
  }

  .yp-result-page__description {
    margin-inline: auto;
  }

  .yp-result-page__actions {
    justify-content: center;
  }
}
</style>
