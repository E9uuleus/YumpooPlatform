<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Bell, Loading, WarningFilled } from '@element-plus/icons-vue'
import { ElBadge, ElButton, ElIcon, ElPopover, ElRadioButton, ElRadioGroup } from 'element-plus'
import type { NotificationItem } from '@yumpoo/api-client'
import { useInbox, useInboxList, type InboxFilter } from '../../composables/useInbox'
import { useSession } from '../../composables/useSession'
import { inboxEmptyCopy, notificationLink } from './inboxPresentation'
import InboxRow from './InboxRow.vue'
import YpEmptyState from '../yp/YpEmptyState.vue'

const inbox = useInbox(), session = useSession(), router = useRouter()
const open = ref(false), pulse = ref(false), filter = ref<InboxFilter>('unread'), marking = ref(false)
const list = useInboxList(filter, open)
const emptyCopy = computed(() => inboxEmptyCopy(filter.value))
watch(open, value => { if (value) { filter.value = inbox.unread.value ? 'unread' : 'all'; void inbox.refresh() } })
watch(inbox.unread, (count, old) => { if (count > old) pulse.value = true })
async function openItem(item: NotificationItem) {
  const link = notificationLink(item, session.authentication.value?.user.id)
  if (!link) return
  if (item.state === 'UNREAD') void list.setState(item, 'read')
  open.value = false
  await router.push(link)
}
async function readAll() {
  if (!list.serverNow.value) return
  marking.value = true
  if (!await inbox.readAll(list.serverNow.value, filter.value)) list.error.value = '操作未完成，请重试。'
  marking.value = false
}
</script>

<template>
  <el-popover
    v-model:visible="open"
    placement="bottom-end"
    :width="420"
    trigger="click"
    popper-class="inbox-popover"
  >
    <template #reference>
      <el-button
        class="inbox-trigger"
        :class="{ 'is-open': open }"
        :aria-label="`收件箱，${inbox.unread.value} 条未读`"
      >
        <el-badge
          :value="inbox.unread.value"
          :max="99"
          :hidden="!inbox.unread.value"
          :class="{ 'inbox-pulse': pulse }"
          @animationend="pulse = false"
        >
          <el-icon><bell /></el-icon>
        </el-badge>
      </el-button>
    </template>
    <section
      class="inbox-panel"
      aria-label="收件箱"
    >
      <header class="inbox-panel__header">
        <div class="inbox-panel__title">
          <strong>收件箱</strong><span
            v-if="inbox.unread.value"
            class="inbox-panel__count"
          >{{ inbox.unread.value > 99 ? '99+' : inbox.unread.value }}</span>
        </div>
        <el-button
          link
          type="primary"
          :disabled="!inbox.unread.value || !list.serverNow.value"
          :loading="marking"
          @click="readAll"
        >
          全部标为已读
        </el-button>
      </header>
      <el-radio-group
        v-model="filter"
        class="inbox-panel__filters"
        size="small"
        aria-label="通知筛选"
      >
        <el-radio-button value="unread">
          未读
        </el-radio-button><el-radio-button value="all">
          全部
        </el-radio-button><el-radio-button value="mention">
          @我
        </el-radio-button>
      </el-radio-group>
      <div
        class="inbox-panel__list"
        :aria-busy="list.loading.value"
      >
        <inbox-row
          v-for="item in list.items.value"
          :key="item.id"
          :item="item"
          :reader-id="session.authentication.value?.user.id"
          :now="inbox.counts.value?.serverNow || new Date()"
          :timezone="session.authentication.value?.company?.timezone"
          compact
          @open="openItem"
          @change="list.setState"
        />
        <p
          v-if="list.error.value"
          class="inbox-panel__status inbox-panel__status--error"
          role="status"
        >
          <el-icon aria-hidden="true">
            <warning-filled />
          </el-icon>{{ list.error.value }}<el-button
            link
            type="primary"
            @click="list.load()"
          >
            重试
          </el-button>
        </p>
        <yp-empty-state
          v-else-if="!list.items.value.length && !list.loading.value"
          compact
          :title="emptyCopy.title"
          :description="emptyCopy.description"
        />
        <p
          v-if="list.loading.value && !list.items.value.length"
          class="inbox-panel__status"
          role="status"
        >
          <el-icon
            class="is-loading"
            aria-hidden="true"
          >
            <loading />
          </el-icon>正在加载…
        </p>
      </div>
      <footer class="inbox-panel__footer">
        <el-button
          text
          @click="open = false; router.push('/inbox')"
        >
          打开收件箱<el-icon class="el-icon--right">
            <arrow-right />
          </el-icon>
        </el-button>
      </footer>
    </section>
  </el-popover>
</template>

<style scoped>
.inbox-trigger { width: 36px; padding: 0; background: transparent; border-color: transparent; }
.inbox-trigger.is-open { background: var(--yp-bg-hover); }
.inbox-trigger .el-icon { font-size: 18px; }
.inbox-trigger :deep(.el-badge__content) { background: var(--yp-status-red); font-variant-numeric: tabular-nums; }

.inbox-panel { display: grid; }
.inbox-panel__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--yp-space-2);
  padding: 14px 16px 12px;
}
.inbox-panel__title { display: flex; align-items: center; gap: var(--yp-space-2); }
.inbox-panel__title strong { color: var(--yp-text-primary); font-family: var(--yp-font-heading); font-size: 15px; font-weight: 600; }
.inbox-panel__count {
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--yp-radius-pill);
  color: var(--yp-status-blue-foreground);
  background: var(--yp-action-primary);
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.inbox-panel__header .el-button { min-height: 0; font-size: 13px; }

.inbox-panel__filters {
  --el-radio-button-checked-bg-color: var(--yp-bg-raised);
  --el-radio-button-checked-text-color: var(--yp-text-primary);
  --el-radio-button-checked-border-color: transparent;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0 16px 12px;
  padding: 3px;
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-sunken);
}
.inbox-panel__filters :deep(.el-radio-button__inner) {
  width: 100%;
  padding: 6px 0;
  border: 0 !important;
  border-radius: var(--yp-radius-sm) !important;
  outline: none;
  color: var(--yp-text-secondary);
  background: transparent;
  box-shadow: none !important;
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard), background-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.inbox-panel__filters :deep(.el-radio-button__inner:hover) { color: var(--yp-text-primary); }
.inbox-panel__filters :deep(.el-radio-button.is-active .el-radio-button__inner) {
  color: var(--yp-text-primary);
  background: var(--yp-bg-raised);
  box-shadow: 0 1px 2px color-mix(in srgb, var(--yp-text-primary) 14%, transparent) !important;
}
.inbox-panel__filters :deep(.el-radio-button__original-radio:focus-visible + .el-radio-button__inner) {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: -2px;
}

.inbox-panel__list {
  --inbox-surface: var(--yp-bg-raised);
  min-height: 120px;
  max-height: min(420px, 60vh);
  overflow: auto;
  border-top: 1px solid var(--yp-border-subtle);
  overscroll-behavior: contain;
  scrollbar-color: var(--yp-border-strong) transparent;
  scrollbar-width: thin;
}
.inbox-panel__list > .inbox-row + .inbox-row { border-top: 1px solid var(--yp-border-subtle); }
.inbox-panel__status {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 40px 16px;
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}
.inbox-panel__status--error { padding: 16px; color: var(--yp-text-secondary); }
.inbox-panel__status--error .el-icon { color: var(--yp-status-red); }

.inbox-panel__footer { padding: 4px; border-top: 1px solid var(--yp-border-subtle); }
.inbox-panel__footer .el-button { width: 100%; min-height: 36px; color: var(--yp-text-secondary); font-size: 13px; }
.inbox-panel__footer .el-button:hover { color: var(--yp-link); }

.inbox-pulse :deep(.el-badge__content) { animation: inbox-arrive .25s ease-out; }
@keyframes inbox-arrive { from { transform: translateY(-50%) translateX(100%) scale(.7); } to { transform: translateY(-50%) translateX(100%) scale(1); } }
@media (prefers-reduced-motion: reduce) { .inbox-pulse :deep(.el-badge__content) { animation: none; } }
</style>
<style>
.inbox-popover.el-popover {
  --el-popover-padding: 0;
  max-width: calc(100vw - 24px);
  box-sizing: border-box;
  padding: 0;
  border-color: var(--yp-border-default);
  border-radius: var(--yp-radius-xl);
  background: var(--yp-bg-raised);
  box-shadow: var(--yp-shadow-popover);
}
</style>
