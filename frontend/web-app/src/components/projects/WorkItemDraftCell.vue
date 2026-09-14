<script setup lang="ts">
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import YpAssignee from '../yp/YpAssignee.vue'
import { workItemLabelColorValue } from './workItemLabelColors'
import './workItemTimer.css'

defineProps<{ item: ProjectWorkItemListItem; column: string; statusLabel: string; statusColor?: string | undefined }>()
</script>

<template>
  <div
    class="work-item-draft-cell"
    aria-disabled="true"
  >
    <yp-assignee
      v-if="column === 'assignee'"
      :user-id="null"
      :display-name="null"
      :show-name="false"
      size="table"
    />
    <span
      v-else-if="column === 'content'"
      class="work-item-draft-content"
      :style="{ backgroundColor: workItemLabelColorValue(item.contentColorToken) }"
    >{{ item.contentName }}</span>
    <span
      v-else-if="column === 'status' || column === 'priority'"
      class="work-item-draft-label"
      :style="{ backgroundColor: workItemLabelColorValue(column === 'status' ? statusColor : undefined) }"
    >
      {{ column === 'status' ? statusLabel : '—' }}
    </span>
    <span
      v-else-if="column === 'timeTracking'"
      class="timer-cell"
    >
      <span
        class="timer-toggle"
        aria-hidden="true"
      >
        <svg viewBox="0 0 16 16"><path d="M5 2.8v10.4L13 8z" /></svg>
      </span>
      <span class="duration">0m 0s</span>
    </span>
    <span v-else>—</span>
  </div>
</template>

<style scoped>
.work-item-draft-cell { display: flex; width: 100%; height: 100%; align-items: center; justify-content: center; color: var(--yp-text-muted); }
.work-item-draft-label { display: flex; width: 100%; align-self: stretch; align-items: center; justify-content: center; color: var(--yp-text-inverse); }
.work-item-draft-content { display: block; width: calc(100% - 32px); height: 26px; line-height: 26px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border-radius: 20px; color: var(--yp-text-inverse); }
</style>

<style>
tr.work-item-draft-row > td.el-table__cell { position: relative; }
tr.work-item-draft-row > td.el-table__cell::after, tr.work-item-draft-row .monday-discussion-btn::after, tr.work-item-draft-row .subitem-discussion::after { position: absolute; z-index: 9; inset: 0; background: rgb(255 255 255 / 68%); content: ''; cursor: default; }
tr.work-item-draft-row > td.monday-title-column::after { display: none; }
tr.work-item-draft-row .monday-discussion-btn, tr.work-item-draft-row .subitem-discussion { position: relative; }
</style>
