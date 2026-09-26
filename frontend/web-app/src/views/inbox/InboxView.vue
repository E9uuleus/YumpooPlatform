<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import type { NotificationItem } from '@yumpoo/api-client'
import { useInbox, useInboxList } from '../../composables/useInbox'
import { useSession } from '../../composables/useSession'
import { notificationDateGroup, notificationLink, parseInboxFilter } from '../../components/inbox/inboxPresentation'
import InboxRow from '../../components/inbox/InboxRow.vue'
import YpPageHeader from '../../components/yp/YpPageHeader.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'

const route = useRoute(), router = useRouter(), session = useSession(), inbox = useInbox()
const filter = computed(() => parseInboxFilter(route.query.filter))
const list = useInboxList(filter)
const marking = ref(false), sentinel = ref<HTMLElement>()
const now = computed(() => inbox.counts.value?.serverNow || new Date())
const groups = computed(() => {
  const result: { label: string; items: NotificationItem[] }[] = []
  for (const item of list.items.value) {
    const label = notificationDateGroup(item.createdAt, now.value, session.authentication.value?.company.timezone || 'UTC')
    let group = result.find(entry => entry.label === label)
    if (!group) { group = { label, items: [] }; result.push(group) }
    group.items.push(item)
  }
  return result
})
async function openItem(item: NotificationItem) {
  const link = notificationLink(item, session.authentication.value?.user.id)
  if (!link) return
  if (item.state === 'UNREAD') void list.setState(item, 'read')
  await router.push(link)
}
async function readAll() {
  if (!list.serverNow.value) return
  marking.value = true
  if (!await inbox.readAll(list.serverNow.value, filter.value)) list.error.value = '操作未完成，请重试。'
  marking.value = false
}
let observer: IntersectionObserver | undefined
onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting) && !list.error.value) void list.load(true)
  }, { rootMargin: '160px' })
  if (sentinel.value) observer.observe(sentinel.value)
})
watch(() => list.loading.value, loading => {
  if (!loading && list.cursor.value && sentinel.value && !list.error.value) {
    const rect = sentinel.value.getBoundingClientRect()
    if (rect.height > 0 && rect.top <= window.innerHeight + 160) void list.load(true)
  }
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <section class="inbox-page">
    <yp-page-header title="收件箱">
      <template #actions>
        <el-button
          v-if="filter !== 'archived'"
          :disabled="!inbox.unread.value || !list.serverNow.value"
          :loading="marking"
          @click="readAll"
        >
          全部标为已读
        </el-button>
      </template>
    </yp-page-header>
    <div
      class="inbox-page__list"
      :aria-busy="list.loading.value"
    >
      <section
        v-for="group in groups"
        :key="group.label"
        class="inbox-date-group"
      >
        <h2>{{ group.label }}</h2><inbox-row
          v-for="item in group.items"
          :key="item.id"
          :item="item"
          :reader-id="session.authentication.value?.user.id"
          :now="now"
          @open="openItem"
          @change="list.setState"
        />
      </section>
      <yp-empty-state
        v-if="!list.items.value.length && !list.loading.value && !list.error.value"
        title="没有新通知"
        description="新的评论、指派与项目动态会显示在这里。"
      />
      <div
        ref="sentinel"
        class="inbox-page__more"
      >
        <p
          v-if="list.error.value"
          role="status"
        >
          {{ list.error.value }}<el-button
            link
            @click="list.load(!!list.cursor.value)"
          >
            重试
          </el-button>
        </p><span
          v-else-if="list.loading.value"
          role="status"
        >正在加载…</span><el-button
          v-else-if="list.cursor.value"
          link
          @click="list.load(true)"
        >
          加载更多
        </el-button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.inbox-page{max-width:1080px;margin:0 auto;padding:var(--yp-space-6);width:100%;box-sizing:border-box}
.inbox-date-group h2{position:sticky;top:0;z-index:1;margin:0;padding:12px 16px;background:var(--yp-bg-surface);color:var(--yp-text-muted);font-size:var(--yp-type-caption-size);font-weight:600;border-bottom:1px solid var(--yp-border-subtle)}
.inbox-date-group+.inbox-date-group{margin-top:var(--yp-space-5)}
.inbox-page__more{min-height:48px;display:flex;justify-content:center;align-items:center;color:var(--yp-text-muted);font-size:var(--yp-type-caption-size)}
@media(max-width:640px){.inbox-page{padding:var(--yp-space-3)}}
</style>
