<script setup lang="ts">
import { computed } from 'vue'
import { getStatusPresentation, type StatusDomain } from '../../design-system/status'

const props = withDefaults(defineProps<{
  domain: StatusDomain
  status: string
  effect?: 'solid' | 'soft' | 'cell'
  size?: 'small' | 'default'
}>(), {
  effect: 'solid',
  size: 'default',
})

const presentation = computed(() => getStatusPresentation(props.domain, props.status))
</script>

<template>
  <span
    class="yp-status-tag"
    :class="[
      `yp-status-tag--${presentation.tone}`,
      `yp-status-tag--${effect}`,
      `yp-status-tag--${size}`,
    ]"
  >
    {{ presentation.label }}
  </span>
</template>

<style scoped>
.yp-status-tag {
  --yp-status-color: var(--yp-status-gray);
  --yp-status-foreground: var(--yp-status-gray-foreground);
  display: inline-flex;
  align-items: center;
  min-height: var(--yp-status-height);
  padding: 0 var(--yp-space-3);
  border: 1px solid transparent;
  border-radius: var(--yp-radius-sm);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
  line-height: var(--yp-type-caption-line);
  white-space: nowrap;
}

.yp-status-tag--small {
  min-height: calc(var(--yp-status-height) - 4px);
  padding-inline: var(--yp-space-2);
}

.yp-status-tag--solid {
  color: var(--yp-status-foreground);
  background: var(--yp-status-color);
}

.yp-status-tag--soft {
  color: var(--yp-text-primary);
  border-color: color-mix(in srgb, var(--yp-status-color) var(--yp-status-border-mix), var(--yp-border-default));
  background: color-mix(in srgb, var(--yp-status-color) var(--yp-status-surface-mix), var(--yp-bg-surface));
}

.yp-status-tag--cell {
  width: 100%;
  min-height: var(--yp-table-row-height);
  justify-content: center;
  border-radius: 0;
  color: var(--yp-status-foreground);
  background: var(--yp-status-color);
}

.yp-status-tag--blue { --yp-status-color: var(--yp-status-blue); --yp-status-foreground: var(--yp-status-blue-foreground); }
.yp-status-tag--green { --yp-status-color: var(--yp-status-green); --yp-status-foreground: var(--yp-status-green-foreground); }
.yp-status-tag--yellow { --yp-status-color: var(--yp-status-yellow); --yp-status-foreground: var(--yp-status-yellow-foreground); }
.yp-status-tag--red { --yp-status-color: var(--yp-status-red); --yp-status-foreground: var(--yp-status-red-foreground); }
.yp-status-tag--purple { --yp-status-color: var(--yp-status-purple); --yp-status-foreground: var(--yp-status-purple-foreground); }
.yp-status-tag--teal { --yp-status-color: var(--yp-status-teal); --yp-status-foreground: var(--yp-status-teal-foreground); }
.yp-status-tag--pink { --yp-status-color: var(--yp-status-pink); --yp-status-foreground: var(--yp-status-pink-foreground); }
.yp-status-tag--gray { --yp-status-color: var(--yp-status-gray); --yp-status-foreground: var(--yp-status-gray-foreground); }
</style>
