<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElIcon, ElSkeleton, ElSkeletonItem } from 'element-plus'
import { Check, Loading, WarningFilled } from '@element-plus/icons-vue'
import type { NotificationItem } from '@yumpoo/api-client'
import { useInbox, useInboxList } from '../../composables/useInbox'
import { useSession } from '../../composables/useSession'
import { inboxEmptyCopy, inboxFilterLabel, notificationDateGroup, notificationLink, parseInboxFilter } from '../../components/inbox/inboxPresentation'
import InboxRow from '../../components/inbox/InboxRow.vue'
import YpPageHeader from '../../components/yp/YpPageHeader.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'

const route = useRoute(), router = useRouter(), session = useSession(), inbox = useInbox()
const filter = computed(() => parseInboxFilter(route.query.filter))
const list = useInboxList(filter)
const marking = ref(false), sentinel = ref<HTMLElement>()
const now = computed(() => inbox.counts.value?.serverNow || new Date())
const timezone = computed(() => session.authentication.value?.company.timezone || 'UTC')
const emptyCopy = computed(() => inboxEmptyCopy(filter.value))
const initialLoading = computed(() => list.loading.value && !list.items.value.length)
const skeletonWidths = [72, 54, 66, 48]
const groups = computed(() => {
  const result: { label: string; items: NotificationItem[] }[] = []
  for (const item of list.items.value) {
    const label = notificationDateGroup(item.createdAt, now.value, timezone.value)
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
      <template #meta>
        <span class="inbox-page__scope">{{ inboxFilterLabel(filter) }}</span><span class="inbox-page__summary">{{ inbox.unread.value ? `${inbox.unread.value} 条未读` : '没有未读通知' }}</span>
      </template>
      <template #actions>
        <el-button
          v-if="filter !== 'archived'"
          :icon="Check"
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
      <div
        v-if="initialLoading"
        class="inbox-skeleton"
        role="status"
        aria-label="正在加载通知"
      >
        <el-skeleton
          v-for="width in skeletonWidths"
          :key="width"
          class="inbox-skeleton__row"
          animated
        >
          <template #template>
            <el-skeleton-item
              variant="circle"
              class="inbox-skeleton__avatar"
            />
            <div class="inbox-skeleton__lines">
              <el-skeleton-item
                variant="text"
                :style="{ width: `${width}%` }"
              />
              <el-skeleton-item
                variant="text"
                :style="{ width: `${width / 2}%` }"
              />
            </div>
          </template>
        </el-skeleton>
      </div>
      <section
        v-for="group in groups"
        :key="group.label"
        class="inbox-date-group"
      >
        <header class="inbox-date-group__header">
          <h2>{{ group.label }}</h2><span>{{ group.items.length }}</span>
        </header>
        <div class="inbox-date-group__rows">
          <inbox-row
            v-for="item in group.items"
            :key="item.id"
            :item="item"
            :reader-id="session.authentication.value?.user.id"
            :now="now"
            :timezone="timezone"
            @open="openItem"
            @change="list.setState"
          />
        </div>
      </section>
      <yp-empty-state
        v-if="!list.items.value.length && !list.loading.value && !list.error.value"
        :title="emptyCopy.title"
        :description="emptyCopy.description"
      >
        <template
          v-if="filter === 'unread'"
          #action
        >
          <el-button @click="router.push({ query: { ...route.query, filter: 'all' } })">
            查看全部通知
          </el-button>
        </template>
      </yp-empty-state>
      <div
        ref="sentinel"
        class="inbox-page__more"
      >
        <p
          v-if="list.error.value"
          class="inbox-page__error"
          role="status"
        >
          <el-icon aria-hidden="true">
            <warning-filled />
          </el-icon>{{ list.error.value }}<el-button
            link
            type="primary"
            @click="list.load(!!list.cursor.value)"
          >
            重试
          </el-button>
        </p><span
          v-else-if="list.loading.value && list.items.value.length"
          class="inbox-page__loading"
          role="status"
        ><el-icon
          class="is-loading"
          aria-hidden="true"
        ><loading /></el-icon>正在加载…</span><el-button
          v-else-if="list.cursor.value"
          link
          @click="list.load(true)"
        >
          加载更多
        </el-button><span
          v-else-if="list.items.value.length"
          class="inbox-page__end"
        >已显示全部通知</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.inbox-page { width: 100%; max-width: 880px; margin: 0 auto; }
.inbox-page__scope {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: var(--yp-radius-pill);
  color: var(--yp-link);
  background: var(--yp-bg-selected);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
}
.inbox-page__summary { color: var(--yp-text-secondary); font-size: 13px; font-variant-numeric: tabular-nums; }

.inbox-date-group + .inbox-date-group { margin-top: var(--yp-space-5); }
.inbox-date-group__header {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: baseline;
  gap: var(--yp-space-2);
  padding: 10px 2px 8px;
  background: var(--yp-bg-surface);
}
.inbox-date-group__header h2 {
  margin: 0;
  color: var(--yp-text-primary);
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
}
.inbox-date-group__header span { color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); font-variant-numeric: tabular-nums; }
.inbox-date-group__rows,
.inbox-skeleton {
  overflow: hidden;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}
.inbox-date-group__rows > .inbox-row + .inbox-row { border-top: 1px solid var(--yp-border-subtle); }

.inbox-skeleton { margin-top: 38px; }
.inbox-skeleton__row { display: grid; grid-template-columns: 32px minmax(0, 1fr); gap: 12px; padding: 16px 20px; }
.inbox-skeleton__row + .inbox-skeleton__row { border-top: 1px solid var(--yp-border-subtle); }
.inbox-skeleton__avatar { width: 32px; height: 32px; }
.inbox-skeleton__lines { display: grid; gap: 10px; padding-top: 3px; }

.inbox-page__more {
  display: flex;
  min-height: 64px;
  justify-content: center;
  align-items: center;
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}
.inbox-page__error,
.inbox-page__loading { display: inline-flex; align-items: center; gap: 6px; margin: 0; }
.inbox-page__error { color: var(--yp-text-secondary); }
.inbox-page__error .el-icon { color: var(--yp-status-red); font-size: 14px; }
.inbox-page__error .el-button { margin-left: 4px; }
.inbox-page__end { display: flex; width: 100%; align-items: center; gap: var(--yp-space-3); }
.inbox-page__end::before,
.inbox-page__end::after { flex: 1; height: 1px; background: var(--yp-border-subtle); content: ""; }
</style>
