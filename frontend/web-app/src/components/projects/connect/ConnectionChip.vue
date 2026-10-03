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
.connection-chip { display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; max-width: 160px; height: 22px; box-sizing: border-box; padding: 0 8px 0 6px; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-pill); background: var(--yp-bg-sunken); color: var(--yp-text-primary); font: inherit; font-size: 12px; line-height: 20px; white-space: nowrap; cursor: pointer; }
.connection-chip:hover { background: var(--yp-bg-hover); }
.connection-chip--archived { color: var(--yp-text-muted); }
.connection-chip__dot { width: 8px; height: 8px; flex-shrink: 0; border-radius: var(--yp-radius-pill); }
.connection-chip__title { overflow: hidden; text-overflow: ellipsis; }
.connection-chip:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: 1px; }
</style>
