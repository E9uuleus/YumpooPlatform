<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElIcon, ElTooltip } from 'element-plus'
import { Check, FolderOpened, Lock, Message } from '@element-plus/icons-vue'
import type { NotificationItem } from '@yumpoo/api-client'
import YpAssignee from '../yp/YpAssignee.vue'
import { formatChineseTimestamp } from '../../design-system/dates'
import { isOwnProjectRemoval, notificationKind, notificationLink, notificationText, relativeTime, type NotificationKind } from './inboxPresentation'

const props = defineProps<{ item: NotificationItem; readerId?: string | undefined; now: Date; compact?: boolean; timezone?: string | undefined }>()
defineEmits<{ open: [item: NotificationItem]; change: [item: NotificationItem, action: 'read' | 'unread' | 'archive'] }>()
const link = computed(() => notificationLink(props.item, props.readerId))
const showActor = computed(() => props.item.target.accessible || isOwnProjectRemoval(props.item, props.readerId))
const unread = computed(() => props.item.state === 'UNREAD')
const kind = computed(() => notificationKind(props.item))
const kindGlyphs: Record<NotificationKind, string> = {
  mention: '',
  comment: 'M3 2.5h10A1.5 1.5 0 0 1 14.5 4v6a1.5 1.5 0 0 1-1.5 1.5H7.2l-2.8 2.1a.5.5 0 0 1-.8-.4v-1.7H3A1.5 1.5 0 0 1 1.5 10V4A1.5 1.5 0 0 1 3 2.5Z',
  assigned: 'M8 8a2.75 2.75 0 1 0 0-5.5A2.75 2.75 0 0 0 8 8Zm-5.25 5.25C2.75 10.9 5.1 9.5 8 9.5s5.25 1.4 5.25 3.75v.25H2.75v-.25Z',
  project: 'M1.5 4A1.5 1.5 0 0 1 3 2.5h3.1a1.5 1.5 0 0 1 1.06.44L8.2 4H13a1.5 1.5 0 0 1 1.5 1.5V12a1.5 1.5 0 0 1-1.5 1.5H3A1.5 1.5 0 0 1 1.5 12V4Z',
}
const canToggleRead = computed(() => props.item.target.accessible && props.item.state !== 'ARCHIVED')
const canArchive = computed(() => props.item.state !== 'ARCHIVED')
const exactTime = computed(() => formatChineseTimestamp(props.item.createdAt, props.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone))
</script>

<template>
  <article
    class="inbox-row"
    :class="{ 'is-unread': unread, 'is-inaccessible': !showActor, 'is-compact': compact }"
  >
    <div
      class="inbox-row__body"
      :role="link ? 'link' : undefined"
      :tabindex="link ? 0 : undefined"
      @click="link && $emit('open', item)"
      @keydown.enter.prevent="link && $emit('open', item)"
    >
      <span class="inbox-row__avatar">
        <yp-assignee
          v-if="showActor"
          :user-id="item.actor?.id"
          :display-name="item.actor?.displayName || '系统'"
          :show-name="false"
        />
        <span
          v-else
          class="inbox-row__placeholder"
          aria-hidden="true"
        ><el-icon><lock /></el-icon></span>
        <span
          v-if="showActor"
          class="inbox-row__kind"
          :class="`inbox-row__kind--${kind}`"
          aria-hidden="true"
        ><b v-if="kind === 'mention'">@</b><svg
          v-else
          viewBox="0 0 16 16"
          fill="currentColor"
        ><path :d="kindGlyphs[kind]" /></svg></span>
      </span>
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
          v-if="!compact && kind !== 'project' && item.target.accessible && item.target.projectName"
          class="inbox-row__project"
        ><el-icon aria-hidden="true"><folder-opened /></el-icon>{{ item.target.projectName }}</span>
      </div>
      <div class="inbox-row__meta">
        <time
          :datetime="item.createdAt.toISOString()"
          :title="exactTime"
        >{{ relativeTime(item.createdAt, now) }}</time><span
          v-if="unread"
          class="inbox-row__dot"
          aria-label="未读"
        />
      </div>
    </div>
    <div
      v-if="canToggleRead || canArchive"
      class="inbox-row__actions"
    >
      <el-tooltip
        v-if="canToggleRead"
        :content="unread ? '标为已读' : '标为未读'"
        placement="top"
        :show-after="300"
      >
        <el-button
          class="inbox-row__action"
          text
          size="small"
          :aria-label="unread ? '标为已读' : '标为未读'"
          @click="$emit('change', item, unread ? 'read' : 'unread')"
        >
          <el-icon><check v-if="unread" /><message v-else /></el-icon>
        </el-button>
      </el-tooltip>
      <el-tooltip
        v-if="canArchive"
        content="归档"
        placement="top"
        :show-after="300"
      >
        <el-button
          class="inbox-row__action"
          text
          size="small"
          aria-label="归档"
          @click="$emit('change', item, 'archive')"
        >
          <el-icon>
            <svg
              viewBox="0 0 20 20"
              fill="none"
              aria-hidden="true"
            >
              <rect x="2.75" y="3.75" width="14.5" height="4" rx="1" stroke="currentColor" stroke-width="1.5" />
              <path d="M4.25 7.75v7.5a1 1 0 0 0 1 1h9.5a1 1 0 0 0 1-1v-7.5M8 11h4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
            </svg>
          </el-icon>
        </el-button>
      </el-tooltip>
    </div>
  </article>
</template>

<style scoped>
.inbox-row {
  --inbox-row-bg: var(--inbox-surface, var(--yp-bg-surface));
  --inbox-row-pad-y: 14px;
  --inbox-row-pad-x: 20px;
  position: relative;
  background: var(--inbox-row-bg);
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.inbox-row.is-unread { --inbox-row-bg: color-mix(in srgb, var(--yp-bg-selected) 55%, var(--inbox-surface, var(--yp-bg-surface))); }
.inbox-row:hover,
.inbox-row:focus-within { --inbox-row-bg: var(--yp-bg-hover); }
.inbox-row.is-compact { --inbox-row-pad-y: 12px; --inbox-row-pad-x: 16px; }

.inbox-row__body {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  align-items: start;
  column-gap: 12px;
  padding: var(--inbox-row-pad-y) var(--inbox-row-pad-x);
  outline: none;
}
.inbox-row__body[role=link] { cursor: pointer; }
.inbox-row__body:focus-visible { box-shadow: inset 0 0 0 2px var(--yp-focus-ring); }

.inbox-row__avatar { position: relative; display: grid; width: 32px; height: 32px; margin-top: 1px; }
.inbox-row__placeholder {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 50%;
  color: var(--yp-text-muted);
  background: var(--yp-bg-sunken);
  font-size: 15px;
}
.inbox-row__kind {
  position: absolute;
  right: -5px;
  bottom: -5px;
  display: grid;
  width: 18px;
  height: 18px;
  place-items: center;
  border-radius: 50%;
  box-shadow: 0 0 0 2px var(--inbox-row-bg);
  font-size: 11px;
  line-height: 1;
  transition: box-shadow var(--yp-motion-fast) var(--yp-ease-standard);
}
.inbox-row__kind b { font-family: var(--yp-font-heading); font-weight: 700; transform: translateY(-.5px); }
.inbox-row__kind svg { width: 10px; height: 10px; }
.inbox-row__kind--mention { color: var(--yp-status-blue-foreground); background: var(--yp-action-primary); }
.inbox-row__kind--comment { color: var(--yp-status-teal-foreground); background: var(--yp-status-teal); }
.inbox-row__kind--assigned { color: var(--yp-status-purple-foreground); background: var(--yp-status-purple); }
.inbox-row__kind--project { color: var(--yp-status-orange-foreground); background: var(--yp-status-orange); }

.inbox-row__copy { min-width: 0; }
.inbox-row__sentence {
  margin: 0;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
  line-height: var(--yp-type-body-line);
  overflow-wrap: anywhere;
}
.inbox-row__sentence strong { color: var(--yp-text-primary); font-weight: 500; }
.is-unread .inbox-row__sentence { color: var(--yp-text-primary); }
.is-unread .inbox-row__sentence strong { font-weight: 600; }
.is-inaccessible .inbox-row__sentence { color: var(--yp-text-muted); }

.inbox-row__excerpt {
  display: -webkit-box;
  margin: 6px 0 0;
  padding-left: 10px;
  overflow: hidden;
  border-left: 2px solid var(--yp-border-default);
  color: var(--yp-text-secondary);
  font-size: 13px;
  line-height: 20px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.inbox-row__project {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 4px;
  height: 22px;
  margin-top: 8px;
  padding: 0 8px;
  overflow: hidden;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-secondary);
  background: color-mix(in srgb, var(--yp-bg-sunken) 80%, var(--inbox-row-bg));
  font-size: var(--yp-type-caption-size);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.inbox-row__project .el-icon { flex: none; font-size: 12px; }

.inbox-row__meta {
  display: flex;
  min-width: 64px;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  height: 22px;
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  transition: opacity var(--yp-motion-fast) var(--yp-ease-standard);
}
.inbox-row__dot { width: 8px; height: 8px; flex: none; border-radius: 50%; background: var(--yp-action-primary); }

.inbox-row__actions {
  position: absolute;
  top: calc(var(--inbox-row-pad-y) - 3px);
  right: calc(var(--inbox-row-pad-x) - 6px);
  display: flex;
  gap: 2px;
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--yp-motion-fast) var(--yp-ease-standard);
}
.inbox-row__actions .el-button + .el-button { margin-left: 0; }
.inbox-row__action.el-button {
  width: 28px;
  min-height: 28px;
  height: 28px;
  padding: 0;
  color: var(--yp-text-secondary);
}
.inbox-row__action.el-button:hover,
.inbox-row__action.el-button:focus-visible {
  color: var(--yp-text-primary);
  background: color-mix(in srgb, var(--yp-text-primary) 9%, transparent);
}
.inbox-row__action .el-icon { font-size: 16px; }
.inbox-row:hover .inbox-row__actions,
.inbox-row:focus-within .inbox-row__actions { opacity: 1; pointer-events: auto; }
.inbox-row:hover .inbox-row__meta,
.inbox-row:focus-within .inbox-row__meta { opacity: 0; }

.is-compact .inbox-row__sentence {
  display: -webkit-box;
  overflow: hidden;
  font-size: 13px;
  line-height: 20px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.is-compact .inbox-row__excerpt { margin-top: 4px; font-size: var(--yp-type-caption-size); line-height: 18px; -webkit-line-clamp: 1; }
.is-compact .inbox-row__meta { height: 20px; }

@media (max-width: 640px) {
  .inbox-row { --inbox-row-pad-x: 14px; }
}

@media (hover: none) {
  .inbox-row__meta,
  .is-compact .inbox-row__meta { height: 52px; align-items: flex-start; line-height: 20px; }
  .inbox-row__dot { margin-top: 6px; }
  .inbox-row__actions {
    top: auto;
    bottom: calc(var(--inbox-row-pad-y) - 4px);
    opacity: 1;
    pointer-events: auto;
  }
  .inbox-row:hover .inbox-row__meta,
  .inbox-row:focus-within .inbox-row__meta { opacity: 1; }
}
</style>
