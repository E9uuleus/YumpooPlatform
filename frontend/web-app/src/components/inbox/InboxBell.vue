<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Bell } from '@element-plus/icons-vue'
import { ElBadge, ElButton, ElIcon, ElPopover, ElRadioButton, ElRadioGroup } from 'element-plus'
import type { NotificationItem } from '@yumpoo/api-client'
import { useInbox, useInboxList, type InboxFilter } from '../../composables/useInbox'
import { useSession } from '../../composables/useSession'
import { notificationLink } from './inboxPresentation'
import InboxRow from './InboxRow.vue'
import YpEmptyState from '../yp/YpEmptyState.vue'

const inbox = useInbox(), session = useSession(), router = useRouter()
const open = ref(false), pulse = ref(false), filter = ref<InboxFilter>('unread'), marking = ref(false)
const list = useInboxList(filter, open)
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
    :width="400"
    trigger="click"
    popper-class="inbox-popover"
  >
    <template #reference>
      <el-button
        class="inbox-trigger"
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
      class="inbox-popover-body"
      aria-label="收件箱"
    >
      <header>
        <strong>收件箱 <span>{{ inbox.unread.value }}</span></strong><el-button
          link
          :disabled="!inbox.unread.value || !list.serverNow.value"
          :loading="marking"
          @click="readAll"
        >
          全部标为已读
        </el-button>
      </header>
      <el-radio-group
        v-model="filter"
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
        class="inbox-popover-list"
        :aria-busy="list.loading.value"
      >
        <inbox-row
          v-for="item in list.items.value"
          :key="item.id"
          :item="item"
          :reader-id="session.authentication.value?.user.id"
          :now="inbox.counts.value?.serverNow || new Date()"
          compact
          @open="openItem"
          @change="list.setState"
        />
        <p
          v-if="list.error.value"
          role="status"
        >
          {{ list.error.value }} <el-button
            link
            @click="list.load()"
          >
            重试
          </el-button>
        </p>
        <yp-empty-state
          v-else-if="!list.items.value.length && !list.loading.value"
          compact
          title="没有新通知"
          description="新的评论、指派与项目动态会显示在这里。"
        />
        <p
          v-if="list.loading.value && !list.items.value.length"
          class="inbox-loading"
          role="status"
        >
          正在加载…
        </p>
      </div>
      <footer>
        <el-button
          link
          @click="open = false; router.push('/inbox')"
        >
          打开收件箱 →
        </el-button>
      </footer>
    </section>
  </el-popover>
</template>

<style scoped>
.inbox-trigger{width:36px;padding:0;background:transparent;border-color:transparent}
.inbox-trigger .el-icon{font-size:18px}
.inbox-trigger :deep(.el-badge__content){background:var(--yp-status-red)}
.inbox-popover-body{display:grid;gap:14px}
header{display:flex;justify-content:space-between;align-items:center;gap:8px}header strong{font-size:16px}header strong span{font-size:12px;color:var(--yp-text-muted);margin-left:4px}
.inbox-popover-list{max-height:360px;overflow:auto;min-height:96px}
footer{border-top:1px solid var(--yp-border-subtle);padding-top:10px;text-align:center}
.inbox-loading{text-align:center;color:var(--yp-text-muted);padding:20px}
.inbox-pulse :deep(.el-badge__content){animation:inbox-arrive .25s ease-out}
@keyframes inbox-arrive{from{transform:translateY(-50%) translateX(100%) scale(.7)}to{transform:translateY(-50%) translateX(100%) scale(1)}}
@media(prefers-reduced-motion:reduce){.inbox-pulse :deep(.el-badge__content){animation:none}}
</style>
<style>
.inbox-popover{max-width:calc(100vw - 24px);box-sizing:border-box}
</style>
