<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import type { ActivityItem } from '@yumpoo/api-client'
import { ElButton, ElDatePicker, ElIcon, ElTooltip } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { activityApi } from '../../api/client'
import { toApiProblem, type ApiProblem } from '../../api/problems'
import { useSession } from '../../composables/useSession'
import { formatChineseTimestamp, formatDateOnly, formatTimestamp } from '../../design-system/dates'
import InlineProblem from '../InlineProblem.vue'
import YpAssignee from '../yp/YpAssignee.vue'
import YpEmptyState from '../yp/YpEmptyState.vue'
import YpSegmented from '../yp/YpSegmented.vue'
import { ACTIVITY_CATEGORIES, ACTIVITY_CATEGORY_ORDER, activityCategory, type ActivityCategory } from './activityPresentation'

const props = defineProps<{
  projectId?: string | undefined
  workItemId?: string | undefined
  compact?: boolean | undefined
}>()

const DAY = 86_400_000
const session = useSession()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const items = ref<ActivityItem[]>([])
const nextCursor = ref<string | null>(null)
const historyStartedAt = ref<Date>()
const category = ref<'all' | ActivityCategory>('all')
const dateRange = ref<[Date, Date] | null>(null)
const loading = ref(false)
const loadingOlder = ref(false)
const error = ref<ApiProblem>()
let requestSequence = 0

const categoryOptions = [
  { value: 'all' as const, label: '全部' },
  ...ACTIVITY_CATEGORY_ORDER.map(key => ({ value: key, label: ACTIVITY_CATEGORIES[key].label })),
]
const rangeShortcuts = [
  { text: '最近 7 天', value: () => [new Date(Date.now() - 6 * DAY), new Date()] },
  { text: '最近 30 天', value: () => [new Date(Date.now() - 29 * DAY), new Date()] },
]
const filtered = computed(() => category.value !== 'all' || Boolean(dateRange.value))

const groups = computed(() => {
  const today = formatDateOnly(new Date(), timezone.value)
  const yesterday = formatDateOnly(new Date(Date.now() - DAY), timezone.value)
  const weekday = new Intl.DateTimeFormat('zh-CN', { timeZone: timezone.value, weekday: 'short' })
  const grouped = new Map<string, ActivityItem[]>()
  for (const item of items.value) {
    const key = formatDateOnly(item.occurredAt, timezone.value)
    const bucket = grouped.get(key)
    if (bucket) bucket.push(item)
    else grouped.set(key, [item])
  }
  return [...grouped.entries()].map(([date, values]) => ({
    date,
    label: `${date === today ? '今天 · ' : date === yesterday ? '昨天 · ' : ''}${date} ${weekday.format(values[0]!.occurredAt)}`,
    items: values,
  }))
})

function startOfDay(value: Date): Date {
  const date = new Date(value)
  date.setHours(0, 0, 0, 0)
  return date
}

function endOfDay(value: Date): Date {
  const date = new Date(value)
  date.setHours(23, 59, 59, 999)
  return date
}

function presentation(item: ActivityItem) {
  return ACTIVITY_CATEGORIES[activityCategory(item.sourceEventType)]
}

function timeOfDay(value: Date): string {
  return formatTimestamp(value, timezone.value).slice(11)
}

async function request(cursor?: string): Promise<Awaited<ReturnType<typeof activityApi.listProjectActivity>>> {
  const common = {
    ...(cursor ? { cursor } : {}),
    size: 25,
    ...(category.value !== 'all' ? { eventType: new Set(ACTIVITY_CATEGORIES[category.value].eventTypes) } : {}),
    ...(dateRange.value ? {
      occurredFrom: startOfDay(dateRange.value[0]),
      occurredTo: endOfDay(dateRange.value[1]),
    } : {}),
  }
  if (props.workItemId) {
    return activityApi.listWorkItemActivity({ workItemId: props.workItemId, ...common })
  }
  if (!props.projectId) throw new Error('Activity scope is missing')
  return activityApi.listProjectActivity({ projectId: props.projectId, ...common })
}

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadingOlder.value = false
  error.value = undefined
  try {
    const page = await request()
    if (sequence !== requestSequence) return
    items.value = page.items
    nextCursor.value = page.nextCursor
    historyStartedAt.value = page.historyStartedAt
  } catch (reason) {
    if (sequence === requestSequence) error.value = await toApiProblem(reason)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

async function loadOlder(): Promise<void> {
  if (!nextCursor.value || loadingOlder.value) return
  const sequence = requestSequence
  const cursor = nextCursor.value
  loadingOlder.value = true
  error.value = undefined
  try {
    const page = await request(cursor)
    if (sequence !== requestSequence) return
    const existing = new Set(items.value.map(item => item.id))
    items.value.push(...page.items.filter(item => !existing.has(item.id)))
    nextCursor.value = page.nextCursor
  } catch (reason) {
    if (sequence === requestSequence) error.value = await toApiProblem(reason)
  } finally {
    if (sequence === requestSequence) loadingOlder.value = false
  }
}

watch([() => props.projectId, () => props.workItemId], () => void load())
watch([category, dateRange], () => void load())
onMounted(() => void load())
</script>

<template>
  <section
    class="activity-timeline"
    :class="{ 'activity-timeline--compact': compact }"
    aria-label="动态时间线"
  >
    <div class="activity-timeline__toolbar">
      <div class="activity-timeline__categories">
        <yp-segmented
          v-model="category"
          :options="categoryOptions"
          label="动态类型"
        />
      </div>
      <div class="activity-timeline__tools">
        <el-date-picker
          v-if="!compact"
          v-model="dateRange"
          type="daterange"
          unlink-panels
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          range-separator="至"
          :shortcuts="rangeShortcuts"
          aria-label="筛选发生日期"
        />
        <el-tooltip
          content="刷新"
          placement="top"
        >
          <el-button
            class="activity-timeline__refresh"
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新动态"
            @click="load"
          />
        </el-tooltip>
      </div>
    </div>
    <div
      v-if="error"
      class="activity-timeline__error"
    >
      <inline-problem
        :problem="error"
        title="动态加载失败"
      />
      <el-button
        type="primary"
        plain
        @click="load"
      >
        重试
      </el-button>
    </div>
    <div
      v-loading="loading"
      class="activity-timeline__feed"
      aria-live="polite"
    >
      <section
        v-for="group in groups"
        :key="group.date"
        class="activity-day"
      >
        <h3 class="activity-timeline__date">
          {{ group.label }}
        </h3>
        <ol class="activity-day__entries">
          <li
            v-for="item in group.items"
            :key="item.id"
            class="activity-entry"
            :style="{ '--activity-tone': presentation(item).tone }"
          >
            <span
              class="activity-entry__marker"
              aria-hidden="true"
            >
              <el-icon>
                <component :is="presentation(item).icon" />
              </el-icon>
            </span>
            <div class="activity-entry__content">
              <p class="activity-entry__summary">
                <span
                  v-if="item.actor.userId"
                  class="activity-entry__avatar"
                >
                  <yp-assignee
                    :user-id="item.actor.userId"
                    :display-name="item.actor.displayName"
                    :show-name="false"
                    size="table"
                  />
                </span>
                <strong>{{ item.actor.displayName }}</strong> {{ item.summary }}
              </p>
              <p class="activity-entry__meta">
                <span>{{ presentation(item).label }}</span>
                <span
                  v-if="item.entityRef"
                  class="activity-entry__ref"
                >{{ item.entityRef }}</span>
              </p>
            </div>
            <el-tooltip
              :content="formatChineseTimestamp(item.occurredAt, timezone)"
              placement="top"
            >
              <time
                class="activity-entry__time"
                :datetime="item.occurredAt.toISOString()"
                tabindex="0"
              >{{ timeOfDay(item.occurredAt) }}</time>
            </el-tooltip>
          </li>
        </ol>
      </section>
      <yp-empty-state
        v-if="!loading && !items.length && !error"
        :reason="filtered ? 'no-results' : 'empty'"
        :title="filtered ? '没有匹配的动态' : '还没有动态'"
        :description="filtered ? '换个类型或日期范围试试。' : '项目中的操作会显示在这里。'"
        compact
      />
      <div
        v-if="nextCursor || historyStartedAt"
        class="activity-timeline__footer"
      >
        <el-button
          v-if="nextCursor"
          class="activity-timeline__older"
          :loading="loadingOlder"
          @click="loadOlder"
        >
          加载更早动态
        </el-button>
        <p
          v-if="historyStartedAt"
          class="activity-timeline__cutover"
        >
          动态从 {{ formatTimestamp(historyStartedAt, timezone) }} 开始记录，更早历史未回填。
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.activity-timeline {
  display: grid;
  gap: var(--yp-space-4);
}

.activity-timeline__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-2) var(--yp-space-3);
}

.activity-timeline__categories {
  max-width: 100%;
  overflow-x: auto;
}

.activity-timeline__tools {
  display: flex;
  align-items: center;
  gap: var(--yp-space-2);
  margin-left: auto;
}

.activity-timeline__tools :deep(.el-date-editor) {
  width: 260px;
}

.activity-timeline__error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-2);
}

.activity-timeline__feed {
  min-height: 200px;
  padding: 0 var(--yp-space-5) var(--yp-space-5);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}

.activity-timeline__date {
  position: sticky;
  top: 0;
  z-index: 1;
  margin: 0;
  padding: var(--yp-space-4) 0 var(--yp-space-2);
  color: var(--yp-text-secondary);
  background: var(--yp-bg-surface);
  font: 600 var(--yp-type-caption-size) / var(--yp-type-caption-line) var(--yp-font-family);
}

.activity-day__entries {
  margin: 0;
  padding: 0;
  list-style: none;
}

.activity-entry {
  position: relative;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--yp-space-3);
  padding: var(--yp-space-3) 0;
}

.activity-entry:not(:last-child)::after {
  position: absolute;
  top: calc(var(--yp-space-3) + 28px);
  bottom: calc(0px - var(--yp-space-3));
  left: 13.5px;
  width: 1px;
  background: var(--yp-border-subtle);
  content: "";
}

.activity-entry__marker {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 50%;
  color: var(--activity-tone);
  background: color-mix(in srgb, var(--activity-tone) 14%, var(--yp-bg-surface));
  font-size: 14px;
}

.activity-entry__content {
  min-width: 0;
}

.activity-entry__summary {
  margin: 0;
  color: var(--yp-text-primary);
  line-height: var(--yp-type-body-line);
  overflow-wrap: anywhere;
}

.activity-entry__summary strong {
  font-weight: 600;
}

.activity-entry__avatar {
  display: inline-flex;
  margin-right: var(--yp-space-2);
  vertical-align: -6px;
}

.activity-entry__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-2);
  margin: 2px 0 0;
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  line-height: var(--yp-type-caption-line);
}

.activity-entry__ref {
  max-width: 100%;
  overflow: hidden;
  padding: 0 var(--yp-space-2);
  border-radius: var(--yp-radius-xs);
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-entry__time {
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  line-height: var(--yp-type-body-line);
  font-variant-numeric: tabular-nums;
}

.activity-entry__time:focus-visible {
  border-radius: var(--yp-radius-xs);
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 2px;
}

.activity-timeline__footer {
  display: grid;
  justify-items: center;
  gap: var(--yp-space-2);
  padding-top: var(--yp-space-4);
}

.activity-timeline__cutover {
  margin: 0;
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}

.activity-timeline--compact .activity-timeline__feed {
  padding: 0;
  border: 0;
}

@media (max-width: 720px) {
  .activity-timeline__tools {
    width: 100%;
    margin-left: 0;
  }

  .activity-timeline__tools :deep(.el-date-editor) {
    flex: 1;
    width: auto;
  }

  .activity-timeline__feed {
    padding: 0 var(--yp-space-3) var(--yp-space-4);
  }
}
</style>
