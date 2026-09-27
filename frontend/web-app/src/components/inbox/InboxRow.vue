<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElIcon } from 'element-plus'
import { Check, Message, FolderRemove } from '@element-plus/icons-vue'
import type { NotificationItem } from '@yumpoo/api-client'
import YpAssignee from '../yp/YpAssignee.vue'
import { isOwnProjectRemoval, notificationLink, notificationText, relativeTime } from './inboxPresentation'

const props = defineProps<{ item: NotificationItem; readerId?: string | undefined; now: Date; compact?: boolean }>()
defineEmits<{ open: [item: NotificationItem]; change: [item: NotificationItem, action: 'read' | 'unread' | 'archive'] }>()
const link = computed(() => notificationLink(props.item, props.readerId))
const showActor = computed(() => props.item.target.accessible || isOwnProjectRemoval(props.item, props.readerId))
</script>

<template>
  <article
    class="inbox-row"
    :class="{ 'is-unread': item.state === 'UNREAD', 'is-inaccessible': !showActor, 'is-compact': compact }"
  >
    <div
      class="inbox-row__body"
      :role="link ? 'link' : undefined"
      :tabindex="link ? 0 : undefined"
      @click="link && $emit('open', item)"
      @keydown.enter.prevent="link && $emit('open', item)"
    >
      <yp-assignee
        :user-id="item.actor?.id"
        :display-name="item.actor?.displayName || '系统'"
        :show-name="false"
      />
      <div class="inbox-row__copy">
        <p class="inbox-row__sentence">
          <strong v-if="showActor">{{ item.actor?.displayName || '系统' }}</strong> {{ notificationText(item, readerId) }}
        </p>
        <p
          v-if="item.target.accessible && item.target.excerpt"
          class="inbox-row__excerpt"
        >
          {{ item.target.excerpt }}
        </p>
        <span
          v-if="!compact && item.target.accessible && item.target.projectName"
          class="inbox-row__project"
        >{{ item.target.projectName }}</span>
      </div>
      <div class="inbox-row__meta">
        <time :datetime="item.createdAt.toISOString()">{{ relativeTime(item.createdAt, now) }}</time><span
          v-if="item.state === 'UNREAD'"
          class="inbox-row__dot"
          aria-label="未读"
        />
      </div>
    </div>
    <div class="inbox-row__actions">
      <el-button
        v-if="item.target.accessible && item.state !== 'ARCHIVED'"
        text
        size="small"
        :aria-label="item.state === 'UNREAD' ? '标为已读' : '标为未读'"
        @click="$emit('change', item, item.state === 'UNREAD' ? 'read' : 'unread')"
      >
        <el-icon><check v-if="item.state === 'UNREAD'" /><message v-else /></el-icon>
      </el-button>
      <el-button
        v-if="item.state !== 'ARCHIVED'"
        text
        size="small"
        aria-label="归档"
        @click="$emit('change', item, 'archive')"
      >
        <el-icon><folder-remove /></el-icon>
      </el-button>
    </div>
  </article>
</template>

<style scoped>
.inbox-row{position:relative;border-bottom:1px solid var(--yp-border-subtle);border-radius:var(--yp-radius-md)}
.inbox-row:hover,.inbox-row:focus-within{background:var(--yp-bg-hover)}
.inbox-row__body{display:flex;align-items:flex-start;gap:12px;padding:16px;outline-offset:-2px}
.inbox-row__body[role=link]{cursor:pointer}
.inbox-row__copy{min-width:0;flex:1}
.inbox-row__sentence{margin:0;color:var(--yp-text-primary);line-height:1.6;font-size:var(--yp-type-body-size)}
.inbox-row__sentence strong{font-weight:600}
.inbox-row__excerpt{margin:4px 0 0;color:var(--yp-text-secondary);font-size:var(--yp-type-caption-size);line-height:1.6;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.inbox-row__project{display:inline-block;margin-top:6px;color:var(--yp-text-muted);font-size:var(--yp-type-caption-size)}
.inbox-row__meta{display:flex;align-items:center;gap:7px;white-space:nowrap;color:var(--yp-text-muted);font-size:11px;padding-top:4px}
.inbox-row__dot{width:6px;height:6px;flex:none;border-radius:50%;background:var(--yp-action-primary)}
.inbox-row__actions{display:flex;justify-content:flex-end;gap:2px;position:absolute;right:10px;bottom:2px;background:var(--yp-bg-surface);border-radius:var(--yp-radius-sm);opacity:0}
.inbox-row__actions .el-button+.el-button{margin-left:0}
.inbox-row:hover .inbox-row__actions,.inbox-row:focus-within .inbox-row__actions{opacity:1}
.is-inaccessible .inbox-row__sentence{color:var(--yp-text-muted)}
.is-compact .inbox-row__body{padding:13px 4px;gap:9px}
.is-compact .inbox-row__sentence{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}
.is-compact .inbox-row__excerpt{-webkit-line-clamp:1}
@media(hover:none){.inbox-row__actions{position:static;opacity:1;background:transparent}}
</style>
