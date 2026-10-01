<script setup lang="ts">
export interface DefinitionItem {
  key: string
  label: string
  value?: string | number | null
  mono?: boolean
}

withDefaults(defineProps<{
  items: readonly DefinitionItem[]
  columns?: 1 | 2 | 3
}>(), {
  columns: 1,
})
</script>

<template>
  <dl
    class="yp-definition-list"
    :class="`yp-definition-list--${columns}`"
  >
    <div
      v-for="item in items"
      :key="item.key"
      class="yp-definition-list__item"
    >
      <dt>{{ item.label }}</dt>
      <dd :class="{ 'yp-definition-list__mono': item.mono }">
        <slot
          :name="item.key"
          :item="item"
        >
          {{ item.value ?? '—' }}
        </slot>
      </dd>
    </div>
  </dl>
</template>

<style scoped>
.yp-definition-list {
  display: grid;
  margin: 0;
  gap: var(--yp-space-3) var(--yp-space-6);
}

.yp-definition-list--2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.yp-definition-list--3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.yp-definition-list--1 .yp-definition-list__item {
  display: grid;
  grid-template-columns: minmax(96px, 30%) minmax(0, 1fr);
  align-items: center;
  gap: var(--yp-space-4);
}

.yp-definition-list__item {
  min-width: 0;
}

dt {
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
  line-height: var(--yp-type-caption-line);
}

.yp-definition-list--1 dt {
  font-size: var(--yp-type-body-size);
  line-height: var(--yp-type-body-line);
}

dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--yp-text-primary);
  font-size: var(--yp-type-body-size);
  line-height: var(--yp-type-body-line);
}

.yp-definition-list--2 dd,
.yp-definition-list--3 dd {
  margin-top: 2px;
}

.yp-definition-list__mono {
  font-family: var(--yp-font-mono);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 720px) {
  .yp-definition-list--2,
  .yp-definition-list--3 {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
