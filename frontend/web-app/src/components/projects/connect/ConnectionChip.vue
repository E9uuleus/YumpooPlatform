<script setup lang="ts">
import type { ConnectionCard } from '@yumpoo/api-client'
import { workItemLabelColorValue } from '../workItemLabelColors'

defineProps<{ card: ConnectionCard; showProject: boolean; tooltip?: string | undefined }>()
const emit = defineEmits<{ click: [] }>()
</script>

<template>
  <button
    type="button"
    class="connection-chip"
    :class="{ 'connection-chip--archived': card.archived }"
    :title="tooltip ?? `${card.projectName} · ${card.itemNo} · ${card.title}`"
    :aria-label="`打开连接：${card.title}`"
    @click.stop="emit('click')"
  >
    <span
      class="connection-chip__dot"
      :style="{ background: workItemLabelColorValue(card.status.colorToken) }"
    />
    <span class="connection-chip__title">{{ showProject ? `${card.projectName} · ${card.title}` : card.title }}</span>
  </button>
</template>

<style scoped>
.connection-chip { display: inline-flex; align-items: center; gap: 5px; flex-shrink: 0; max-width: 160px; height: 20px; box-sizing: border-box; padding: 0 7px 0 5px; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-xs); background: var(--yp-bg-surface); color: var(--yp-text-primary); font: inherit; font-size: 12px; line-height: 18px; white-space: nowrap; cursor: pointer; transition: border-color var(--yp-motion-fast) var(--yp-ease-standard), box-shadow var(--yp-motion-fast) var(--yp-ease-standard); }
.connection-chip:hover { border-color: var(--yp-border-default); box-shadow: var(--yp-shadow-card); }
.connection-chip--archived { color: var(--yp-text-muted); }
.connection-chip__dot { width: 7px; height: 7px; flex-shrink: 0; border-radius: var(--yp-radius-pill); }
.connection-chip__title { overflow: hidden; text-overflow: ellipsis; }
.connection-chip:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: 1px; }
</style>
