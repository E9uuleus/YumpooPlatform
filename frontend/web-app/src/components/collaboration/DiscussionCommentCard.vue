<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElPopover, ElTooltip } from 'element-plus'
import { MoreFilled, Edit, Delete, CollectionTag, ChatLineSquare } from '@element-plus/icons-vue'
import type { WorkItemUpdate } from '@yumpoo/api-client'
import { formatChineseTimestamp, formatRelativeTime } from '../../design-system/dates'
import YpAssignee from '../yp/YpAssignee.vue'
const props = defineProps<{ item: WorkItemUpdate; now: Date; timezone: string; busy: boolean; reply?: boolean }>()
const emit = defineEmits<{ edit: []; delete: []; pin: []; reply: [] }>()
const menu = ref(false)
const menuElement = ref<HTMLElement>()
const moreButton = ref<HTMLButtonElement>()
const hasActions = computed(() => props.item.capabilities.canEdit || props.item.capabilities.canDelete || props.item.capabilities.canPin)
function act(action: 'edit' | 'delete' | 'pin') { menu.value = false; if (action === 'edit') emit('edit'); else if (action === 'delete') emit('delete'); else emit('pin') }
watch(menu, async visible => {
  if (visible) { await nextTick(); menuElement.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus() }
})
function navigateMenu(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault(); event.stopPropagation(); menu.value = false
    void nextTick(() => moreButton.value?.focus())
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const buttons = [...(menuElement.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  if (!buttons.length) return
  event.preventDefault()
  const current = buttons.indexOf(document.activeElement as HTMLButtonElement)
  const index = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
    : (current + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
  buttons[index]?.focus()
}
</script>

<template>
  <article
    class="discussion-update"
    :class="{ 'discussion-update--reply': reply, 'discussion-update--pinned': item.pinnedAt }"
    :data-update-id="item.id"
  >
    <div class="discussion-update__content">
      <yp-assignee
        class="discussion-update__avatar"
        :user-id="item.authorUserId"
        :display-name="item.authorDisplayName"
        :show-name="false"
      />
      <div class="discussion-update__message">
        <header>
          <strong>{{ item.authorDisplayName }}</strong>
          <el-tooltip
            :content="formatChineseTimestamp(item.createdAt, timezone)"
            placement="top"
          >
            <time
              :datetime="item.createdAt.toISOString()"
              tabindex="0"
            >{{ formatRelativeTime(item.createdAt, now, 'day') }}</time>
          </el-tooltip>
          <span
            v-if="item.status === 'EDITED'"
            class="discussion-update__edited"
          >已编辑</span>
          <span
            v-if="item.pinnedAt"
            class="discussion-update__pin"
          ><collection-tag />已置顶</span>
          <el-popover
            v-if="hasActions"
            v-model:visible="menu"
            trigger="click"
            role="menu"
            placement="bottom-end"
            :width="156"
            :show-arrow="false"
            :teleported="true"
          >
            <template #reference>
              <button
                ref="moreButton"
                class="discussion-update__more"
                type="button"
                aria-label="评论操作"
                aria-haspopup="menu"
                :aria-expanded="menu"
                :disabled="busy"
                @keydown.down.prevent="menu = true"
              >
                <more-filled />
              </button>
            </template>
            <div
              ref="menuElement"
              class="discussion-menu"
              aria-label="评论操作"
              @keydown="navigateMenu"
            >
              <button
                v-if="item.capabilities.canPin && !reply"
                role="menuitem"
                :disabled="busy"
                @click="act('pin')"
              >
                <collection-tag />{{ item.pinnedAt ? '取消置顶' : '置顶' }}
              </button>
              <button
                v-if="item.capabilities.canEdit"
                role="menuitem"
                :disabled="busy"
                @click="act('edit')"
              >
                <edit />编辑
              </button>
              <button
                v-if="item.capabilities.canDelete"
                role="menuitem"
                class="discussion-menu__delete"
                :disabled="busy"
                @click="act('delete')"
              >
                <delete />删除
              </button>
            </div>
          </el-popover>
        </header>
        <div
          class="discussion-update__body discussion-rich-text"
          v-html="item.bodyHtml"
        />
      </div>
    </div>
    <div
      v-if="!reply && item.capabilities.canReply"
      class="discussion-update__footer"
    >
      <button
        type="button"
        class="discussion-update__reply"
        :disabled="busy"
        @click="emit('reply')"
      >
        <chat-line-square />回复
      </button>
    </div>
    <slot />
  </article>
</template>

<style scoped>
.discussion-update { min-width: 0; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); overflow: hidden; }
.discussion-update__content { display: flex; gap: 12px; padding: 16px; min-width: 0; }
.discussion-update__avatar { align-self: flex-start; flex: 0 0 auto; }
.discussion-update__message { min-width: 0; flex: 1; }
header { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-height: 32px; color: var(--yp-text-muted); font-size: 13px; }
header strong { color: var(--yp-text-primary); font-size: 14px; }
time { white-space: nowrap; }
.discussion-update__more { margin-left: auto; }
button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 5px 7px; border: 0; border-radius: 4px; color: var(--yp-text-secondary); background: transparent; cursor: pointer; font: inherit; }
button:hover { background: var(--yp-bg-sunken); }
button:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: 2px; }
button:disabled { opacity: .45; cursor: not-allowed; }
svg { width: 18px; height: 18px; flex: 0 0 auto; }
.discussion-update__pin { display: inline-flex; align-items: center; gap: 3px; color: var(--yp-link); font-size: 12px; }
.discussion-update__body { margin-top: 8px; line-height: 1.65; overflow-wrap: anywhere; }
.discussion-update__body :deep(p) { margin: 0 0 8px; }
.discussion-update__footer { display: flex; justify-content: flex-end; padding: 0 12px 8px; }
.discussion-update__reply { opacity: 0; }
.discussion-update:hover > .discussion-update__footer .discussion-update__reply,
.discussion-update:focus-within > .discussion-update__footer .discussion-update__reply { opacity: 1; }
.discussion-update--reply { border: 0; border-radius: 0; background: transparent; overflow: visible; }
.discussion-update--reply .discussion-update__content { padding: 0; }
.discussion-update--reply .discussion-update__message { background: var(--yp-bg-sunken); border-radius: 12px; padding: 8px 12px; flex: 0 1 auto; max-width: calc(100% - 44px); min-width: min(280px, calc(100% - 44px)); }
.discussion-update--reply .discussion-update__avatar { margin-top: 6px; }
.discussion-menu { display: grid; gap: 2px; }
.discussion-menu button { justify-content: flex-start; padding: 8px; text-align: left; }
.discussion-menu__delete { color: var(--yp-status-red, #d83a52); }
@media (hover: none) { .discussion-update__reply { opacity: 1; } }
</style>
