<script setup lang="ts">
withDefaults(defineProps<{
  title?: string | null
  description?: string | null
  flush?: boolean
  tone?: 'default' | 'danger'
  as?: 'section' | 'article' | 'div'
}>(), {
  title: null,
  description: null,
  flush: false,
  tone: 'default',
  as: 'section',
})
</script>

<template>
  <component
    :is="as"
    class="yp-surface"
    :class="[`yp-surface--${tone}`, { 'yp-surface--flush': flush }]"
  >
    <header
      v-if="title || $slots.title || $slots.actions"
      class="yp-surface__header"
    >
      <div class="yp-surface__heading">
        <slot name="title">
          <h2 v-if="title">
            {{ title }}
          </h2>
        </slot>
        <p
          v-if="description"
          class="yp-surface__description"
        >
          {{ description }}
        </p>
      </div>
      <div
        v-if="$slots.actions"
        class="yp-surface__actions"
      >
        <slot name="actions" />
      </div>
    </header>
    <div class="yp-surface__body">
      <slot />
    </div>
    <footer
      v-if="$slots.footer"
      class="yp-surface__footer"
    >
      <slot name="footer" />
    </footer>
  </component>
</template>

<style scoped>
.yp-surface {
  min-width: 0;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  color: var(--yp-text-primary);
  background: var(--yp-bg-surface);
}

.yp-surface--danger {
  border-color: color-mix(in srgb, var(--yp-status-red) 34%, var(--yp-border-subtle));
}

.yp-surface--flush {
  overflow: hidden;
}

.yp-surface__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--yp-space-4);
  padding: var(--yp-card-padding) var(--yp-card-padding) 0;
}

.yp-surface--flush .yp-surface__header {
  padding-bottom: var(--yp-space-3);
  border-bottom: 1px solid var(--yp-border-subtle);
}

.yp-surface__heading {
  min-width: 0;
}

.yp-surface__heading :deep(h2) {
  margin: 0;
  color: var(--yp-text-primary);
  font: 500 var(--yp-type-card-title-size) / var(--yp-type-card-title-line) var(--yp-font-heading);
}

.yp-surface--danger .yp-surface__heading :deep(h2) {
  color: var(--yp-status-red);
}

.yp-surface__description {
  margin: var(--yp-space-1) 0 0;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
  line-height: var(--yp-type-body-line);
}

.yp-surface__actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--yp-space-2);
}

.yp-surface__body {
  min-width: 0;
  padding: var(--yp-card-padding);
}

.yp-surface--flush .yp-surface__body {
  padding: 0;
}

.yp-surface__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--yp-space-3);
  padding: var(--yp-space-3) var(--yp-space-4);
  border-top: 1px solid var(--yp-border-subtle);
}
</style>
