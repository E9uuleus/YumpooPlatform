<script setup lang="ts">
import type { ActivityItem } from '@yumpoo/api-client'
import {
  ElAlert,
  ElButton,
  ElDatePicker,
  ElOption as ElOptionRaw,
  ElSelect as ElSelectRaw,
} from 'element-plus'
import { computed, onMounted, ref, watch, type DefineComponent } from 'vue'
import { activityApi } from '../../api/client'
import { toApiProblem, type ApiProblem } from '../../api/problems'
import { useSession } from '../../composables/useSession'
import { formatDateOnly, formatTimestamp } from '../../design-system/dates'
import InlineProblem from '../InlineProblem.vue'
import YpEmptyState from '../yp/YpEmptyState.vue'

const props = defineProps<{
  projectId?: string | undefined
  workItemId?: string | undefined
  compact?: boolean | undefined
}>()

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const session = useSession()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const items = ref<ActivityItem[]>([])
const nextCursor = ref<string | null>(null)
const historyStartedAt = ref<Date>()
const eventTypes = ref<string[]>([])
const entityTypes = ref<string[]>([])
const occurredRange = ref<[Date, Date] | null>(null)
const loading = ref(false)
const loadingOlder = ref(false)
const error = ref<ApiProblem>()
let requestSequence = 0

const eventOptions = [
  ['catalog.project_updated', '项目资料'],
  ['catalog.project_member_added', '添加成员'],
  ['catalog.project_member_removed', '移除成员'],
  ['workitem.work_item_created', '创建事项'],
  ['workitem.work_item_fields_changed', '修改事项'],
  ['workitem.work_item_status_changed', '状态变化'],
  ['workitem.work_item_update_published', '事项讨论'],
  ['filestorage.attachment_available', '附件可用'],
] as const
const entityOptions = [
  ['PROJECT', '项目'], ['PROJECT_MEMBER', '成员'], ['PRODUCT', '产品'],
  ['CONTENT', '事项集合'], ['WORK_ITEM', '事项'], ['WORK_ITEM_UPDATE', '事项动态'],
  ['WORK_ITEM_RELATION', '事项关系'], ['ATTACHMENT', '附件'],
] as const

const groups = computed(() => {
  const grouped = new Map<string, ActivityItem[]>()
  for (const item of items.value) {
    const key = formatDateOnly(item.occurredAt, timezone.value)
    grouped.set(key, [...(grouped.get(key) ?? []), item])
  }
  return [...grouped.entries()].map(([date, values]) => ({ date, items: values }))
})

async function request(cursor?: string): Promise<Awaited<ReturnType<typeof activityApi.listProjectActivity>>> {
  const common = {
    ...(cursor ? { cursor } : {}),
    size: 25,
    ...(eventTypes.value.length ? { eventType: new Set(eventTypes.value) } : {}),
    ...(entityTypes.value.length ? { entityType: new Set(entityTypes.value) } : {}),
    ...(occurredRange.value ? {
      occurredFrom: occurredRange.value[0],
      occurredTo: occurredRange.value[1],
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

function initials(name: string): string {
  return name.trim().slice(0, 1).toUpperCase() || '系'
}

watch([() => props.projectId, () => props.workItemId], () => void load())
watch([eventTypes, entityTypes, occurredRange], () => void load(), { deep: true })
onMounted(() => void load())
</script>

<template>
  <section class="activity-timeline" :class="{ 'activity-timeline--compact': compact }" aria-label="动态时间线">
    <el-alert
      v-if="historyStartedAt"
      type="info"
      :closable="false"
      show-icon
      class="activity-timeline__cutover"
      :title="`动态从 ${formatTimestamp(historyStartedAt, timezone)} 开始记录，更早历史未回填。`"
    />
    <div class="activity-timeline__toolbar">
      <el-select v-model="eventTypes" multiple clearable collapse-tags placeholder="事件类型" aria-label="筛选事件类型">
        <el-option v-for="option in eventOptions" :key="option[0]" :label="option[1]" :value="option[0]" />
      </el-select>
      <el-select v-model="entityTypes" multiple clearable collapse-tags placeholder="对象类型" aria-label="筛选对象类型">
        <el-option v-for="option in entityOptions" :key="option[0]" :label="option[1]" :value="option[0]" />
      </el-select>
      <el-date-picker
        v-model="occurredRange"
        type="datetimerange"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        range-separator="至"
        aria-label="筛选发生时间"
      />
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <div v-if="error" class="activity-timeline__error">
      <inline-problem :problem="error" title="动态加载失败" />
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>
    <div v-loading="loading" class="activity-timeline__body" aria-live="polite">
      <template v-for="group in groups" :key="group.date">
        <h3 class="activity-timeline__date">{{ group.date }}</h3>
        <article v-for="item in group.items" :key="item.id" class="activity-entry">
          <span class="activity-entry__avatar" aria-hidden="true">{{ initials(item.actor.displayName) }}</span>
          <div class="activity-entry__content">
            <p><strong>{{ item.actor.displayName }}</strong> {{ item.summary }}</p>
            <p class="activity-entry__meta" :title="`requestId: ${item.requestId}`">
              <span>{{ formatTimestamp(item.occurredAt, timezone) }}</span>
              <span>{{ item.entityType }}</span>
              <span v-if="item.entityRef">{{ item.entityRef }}</span>
            </p>
          </div>
        </article>
      </template>
      <yp-empty-state v-if="!loading && !items.length && !error" title="还没有动态" description="切点后的项目操作会显示在这里。" />
    </div>
    <el-button v-if="nextCursor" class="activity-timeline__older" :loading="loadingOlder" @click="loadOlder">
      加载更早动态
    </el-button>
  </section>
</template>

<style scoped>
.activity-timeline { display: grid; gap: var(--yp-space-4); }
.activity-timeline__toolbar { display: flex; flex-wrap: wrap; gap: var(--yp-space-2); align-items: center; }
.activity-timeline__toolbar :deep(.el-select) { width: min(240px, 100%); }
.activity-timeline__error { display: flex; flex-wrap: wrap; gap: var(--yp-space-2); align-items: center; }
.activity-timeline__body { min-height: 160px; }
.activity-timeline__date { position: sticky; top: 0; z-index: 1; margin: var(--yp-space-4) 0 var(--yp-space-2); padding: var(--yp-space-1) 0; color: var(--yp-text-secondary); background: var(--yp-bg-surface); font-size: var(--yp-type-caption-size); }
.activity-entry { display: flex; gap: var(--yp-space-3); padding: var(--yp-space-3) 0; border-bottom: 1px solid var(--yp-border-subtle); }
.activity-entry__avatar { display: grid; width: 34px; height: 34px; flex: 0 0 34px; place-items: center; border-radius: 50%; color: var(--yp-link); background: var(--yp-bg-selected); font-weight: 600; }
.activity-entry__content { min-width: 0; }
.activity-entry__content p { margin: 0; line-height: 1.55; overflow-wrap: anywhere; }
.activity-entry__meta { display: flex; flex-wrap: wrap; gap: var(--yp-space-2); margin-top: var(--yp-space-1) !important; color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.activity-timeline__older { justify-self: center; }
.activity-timeline--compact .activity-timeline__toolbar :deep(.el-select) { width: 180px; }
</style>
