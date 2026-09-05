<script setup lang="ts">
import type {
  WorkItemCellActivityColumn,
  WorkItemCellActivityEntry,
  WorkItemCellActivityFacets,
  WorkItemCellActivityTimeRange,
  WorkItemCellActivityValue,
} from '@yumpoo/api-client'
import { WorkItemCellActivityTimeRange as WorkItemCellActivityTimeRangeValue } from '@yumpoo/api-client'
import { Refresh as RefreshIcon } from '@element-plus/icons-vue'
import { ElAlert, ElButton, ElPopover, ElTooltip } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { activityApi } from '../../api/client'
import { toApiProblem, type ApiProblem } from '../../api/problems'
import { useSession } from '../../composables/useSession'
import { formatChineseTimestamp, formatRelativeTime } from '../../design-system/dates'
import { workItemLabelColorStyle } from '../projects/workItemLabelColors'
import InlineProblem from '../InlineProblem.vue'
import YpAssignee from '../yp/YpAssignee.vue'
import YpEmptyState from '../yp/YpEmptyState.vue'

const props = defineProps<{ workItemId: string }>()
const session = useSession()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const items = ref<WorkItemCellActivityEntry[]>([])
const facets = ref<WorkItemCellActivityFacets>()
const nextCursor = ref<string | null>(null)
const historyStartedAt = ref<Date>()
const selectedTime = ref<WorkItemCellActivityTimeRange>()
const selectedActors = ref<string[]>([])
const selectedColumns = ref<WorkItemCellActivityColumn[]>([])
const filterVisible = ref(false)
const loading = ref(false)
const loadingOlder = ref(false)
const error = ref<ApiProblem>()
const now = ref(new Date())
let minuteTimer: ReturnType<typeof setInterval> | undefined
let requestSequence = 0

const timeLabels: Record<string, string> = {
  TODAY: '今天', YESTERDAY: '昨天', THIS_WEEK: '本周', THIS_MONTH: '本月', THIS_YEAR: '今年',
}
const timeRangeOrder: WorkItemCellActivityTimeRange[] = [
  WorkItemCellActivityTimeRangeValue.Today,
  WorkItemCellActivityTimeRangeValue.Yesterday,
  WorkItemCellActivityTimeRangeValue.ThisWeek,
  WorkItemCellActivityTimeRangeValue.ThisMonth,
  WorkItemCellActivityTimeRangeValue.ThisYear,
]
const timeFacetOptions = computed(() => facets.value?.timeRanges.length
  ? facets.value.timeRanges
  : timeRangeOrder.map(value => ({ value, count: 0, selected: selectedTime.value === value })))
const columnLabels: Record<string, string> = {
  WORK_ITEM_NAME: '工作项名称', ASSIGNEE: '处理人', STATUS: '状态', PRIORITY: '优先级',
  DUE_DATE: '截止日期', CONTENT: '工作项类别',
}
const activeFilterCount = computed(() => (selectedTime.value ? 1 : 0)
  + selectedActors.value.length + selectedColumns.value.length)
const hasFilters = computed(() => activeFilterCount.value > 0)

function request(cursor?: string) {
  return activityApi.listWorkItemCellActivity({
    workItemId: props.workItemId,
    size: 25,
    ...(cursor ? { cursor } : {}),
    ...(selectedTime.value ? { timeRange: selectedTime.value } : {}),
    ...(selectedActors.value.length ? { actorUserId: new Set(selectedActors.value) } : {}),
    ...(selectedColumns.value.length ? { column: new Set(selectedColumns.value) } : {}),
  })
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
    facets.value = page.facets
  } catch (reason) {
    if (sequence === requestSequence) error.value = await toApiProblem(reason)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

async function loadOlder(): Promise<void> {
  if (!nextCursor.value || loadingOlder.value) return
  const sequence = requestSequence
  loadingOlder.value = true
  error.value = undefined
  try {
    const page = await request(nextCursor.value)
    if (sequence !== requestSequence) return
    const known = new Set(items.value.map(item => item.id))
    items.value.push(...page.items.filter(item => !known.has(item.id)))
    nextCursor.value = page.nextCursor
  } catch (reason) {
    if (sequence === requestSequence) error.value = await toApiProblem(reason)
  } finally {
    if (sequence === requestSequence) loadingOlder.value = false
  }
}

function chooseTime(value: WorkItemCellActivityTimeRange): void {
  selectedTime.value = selectedTime.value === value ? undefined : value
}

function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter(item => item !== value) : [...list, value]
}

function clearFilters(): void {
  selectedTime.value = undefined
  selectedActors.value = []
  selectedColumns.value = []
}

function valueText(value: WorkItemCellActivityValue | null): string {
  return value?.displayName ?? ''
}

function isLabelAddition(item: WorkItemCellActivityEntry): boolean {
  return item.changeType === 'ADDED' && item.beforeValue === null
    && (item.column === 'STATUS' || item.column === 'PRIORITY')
    && item.afterValue?.type === 'LABEL'
}

watch(() => props.workItemId, () => void load())
watch([selectedTime, selectedActors, selectedColumns], () => void load(), { deep: true })
onMounted(() => {
  void load()
  minuteTimer = setInterval(() => { now.value = new Date() }, 60_000)
})
onBeforeUnmount(() => { if (minuteTimer) clearInterval(minuteTimer) })
</script>

<template>
  <section class="cell-activity" aria-label="工作项动态">
    <el-alert
      v-if="historyStartedAt"
      class="cell-activity__cutover"
      type="info"
      :closable="false"
      show-icon
      :title="`单元格动态从 ${formatChineseTimestamp(historyStartedAt, timezone)} 开始记录，更早历史未回填。`"
    />

    <div class="cell-activity__toolbar">
      <el-popover
        v-model:visible="filterVisible"
        placement="bottom-start"
        :width="420"
        :show-arrow="true"
        trigger="click"
        popper-class="cell-activity-filter-popover"
      >
        <template #reference>
          <button
            type="button"
            class="cell-activity__filter"
            :class="{ 'is-active': hasFilters, 'is-open': filterVisible }"
            :aria-expanded="filterVisible"
          >
            <span>动态筛选<span v-if="activeFilterCount"> / {{ activeFilterCount }}</span></span>
            <svg class="cell-activity__filter-chevron" viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
          </button>
        </template>
        <div class="activity-filter">
          <header class="activity-filter__header">
            <strong>筛选动态</strong>
            <span class="activity-filter__summary" aria-live="polite">显示 {{ items.length }} 条动态</span>
            <button type="button" :disabled="!hasFilters" @click="clearFilters">清除</button>
          </header>
          <div class="activity-filter__columns">
            <section>
              <h3>时间</h3>
              <button
                v-for="option in timeFacetOptions"
                :key="option.value"
                type="button"
                class="activity-filter__option"
                :class="{ 'is-selected': selectedTime === option.value }"
                @click="chooseTime(option.value)"
              ><span>{{ timeLabels[option.value] }}</span><small>{{ option.count }}</small></button>
            </section>
            <section>
              <h3>成员</h3>
              <button
                v-for="option in facets?.actors ?? []"
                :key="option.userId"
                type="button"
                class="activity-filter__option"
                :class="{ 'is-selected': selectedActors.includes(option.userId) }"
                :aria-label="`按成员 ${option.displayName} 筛选`"
                @click="selectedActors = toggle(selectedActors, option.userId)"
              ><span class="activity-filter__person"><yp-assignee :user-id="option.userId" :display-name="option.displayName" size="table" :show-name="false" /></span><small>{{ option.count }}</small></button>
            </section>
            <section>
              <h3>字段</h3>
              <button
                v-for="option in facets?.columns ?? []"
                :key="option.value"
                type="button"
                class="activity-filter__option"
                :class="{ 'is-selected': selectedColumns.includes(option.value) }"
                @click="selectedColumns = toggle(selectedColumns, option.value)"
              ><span>{{ columnLabels[option.value] }}</span><small>{{ option.count }}</small></button>
            </section>
          </div>
        </div>
      </el-popover>
      <el-tooltip content="刷新动态" placement="top">
        <el-button class="cell-activity__refresh" :loading="loading" aria-label="刷新动态" @click="load">
          <refresh-icon v-if="!loading" class="cell-activity__refresh-icon" aria-hidden="true" />
        </el-button>
      </el-tooltip>
    </div>

    <div v-if="error" class="cell-activity__error">
      <inline-problem :problem="error" title="动态加载失败" />
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <div v-loading="loading" class="cell-activity__list" aria-live="polite">
      <article v-for="item in items" :key="item.id" class="cell-entry">
        <el-tooltip :content="formatChineseTimestamp(item.occurredAt, timezone)" placement="top">
          <span class="cell-entry__time" tabindex="0">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8" /><path d="M12 8v4l3 2" /></svg>
            {{ formatRelativeTime(item.occurredAt, now) }}
          </span>
        </el-tooltip>
        <span class="cell-entry__actor">
          <yp-assignee :user-id="item.actor.userId" :display-name="item.actor.displayName" size="table" :show-name="false" />
          <strong>{{ item.actor.displayName }}</strong>
        </span>
        <span class="cell-entry__column">
          <svg v-if="item.column === 'ASSIGNEE'" viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="8" r="3" /><path d="M3.5 18c.7-3 2.5-4.5 5.5-4.5s4.8 1.5 5.5 4.5M16 8h5M18.5 5.5v5" /></svg>
          <svg v-else-if="item.column === 'DUE_DATE'" viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="5" width="16" height="15" rx="2" /><path d="M8 3v4M16 3v4M4 10h16" /></svg>
          <svg v-else-if="item.column === 'STATUS'" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8" /><path d="m8.5 12 2.2 2.2 4.8-5" /></svg>
          <svg v-else-if="item.column === 'PRIORITY'" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 20V4h10l2 3-2 3H6" /></svg>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h14v14H5zM8 9h8M8 13h6" /></svg>
          {{ columnLabels[item.column] }}
        </span>
        <div class="cell-entry__change">
          <span v-if="item.changeType === 'CREATED'" class="cell-entry__verb">新增</span>
          <span v-else-if="item.changeType === 'ADDED' && !isLabelAddition(item)" class="cell-entry__verb">新增</span>
          <span v-else-if="item.changeType === 'REMOVED'" class="cell-entry__verb cell-entry__verb--removed">移除</span>

          <template v-if="item.changeType === 'REMOVED'">
            <yp-assignee v-if="item.beforeValue?.type === 'MEMBER'" :user-id="item.beforeValue.referenceId" :display-name="item.beforeValue.displayName" size="table" />
            <span v-else-if="item.beforeValue?.type === 'LABEL'" class="cell-entry__label" :title="valueText(item.beforeValue)" :style="workItemLabelColorStyle(item.beforeValue.colorToken ?? undefined)">{{ valueText(item.beforeValue) }}</span>
            <span v-else class="cell-entry__value" :title="valueText(item.beforeValue)">{{ valueText(item.beforeValue) }}</span>
          </template>
          <template v-else-if="item.changeType === 'CHANGED' || isLabelAddition(item)">
            <span v-if="isLabelAddition(item)" class="cell-entry__label cell-entry__label--empty" title="未设置">-</span>
            <yp-assignee v-else-if="item.beforeValue?.type === 'MEMBER'" :user-id="item.beforeValue.referenceId" :display-name="item.beforeValue.displayName" size="table" />
            <span v-else-if="item.beforeValue?.type === 'LABEL'" class="cell-entry__label" :title="valueText(item.beforeValue)" :style="workItemLabelColorStyle(item.beforeValue.colorToken ?? undefined)">{{ valueText(item.beforeValue) }}</span>
            <span v-else class="cell-entry__value cell-entry__old" :title="valueText(item.beforeValue)">{{ valueText(item.beforeValue) }}</span>
            <svg class="cell-entry__arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14M15 8l4 4-4 4" /></svg>
            <yp-assignee v-if="item.afterValue?.type === 'MEMBER'" :user-id="item.afterValue.referenceId" :display-name="item.afterValue.displayName" size="table" />
            <span v-else-if="item.afterValue?.type === 'LABEL'" class="cell-entry__label" :title="valueText(item.afterValue)" :style="workItemLabelColorStyle(item.afterValue.colorToken ?? undefined)">{{ valueText(item.afterValue) }}</span>
            <span v-else class="cell-entry__value" :title="valueText(item.afterValue)">{{ valueText(item.afterValue) }}</span>
          </template>
          <template v-else>
            <yp-assignee v-if="item.afterValue?.type === 'MEMBER'" :user-id="item.afterValue.referenceId" :display-name="item.afterValue.displayName" size="table" />
            <span v-else-if="item.afterValue?.type === 'LABEL'" class="cell-entry__label" :title="valueText(item.afterValue)" :style="workItemLabelColorStyle(item.afterValue.colorToken ?? undefined)">{{ valueText(item.afterValue) }}</span>
            <span v-else class="cell-entry__value" :title="valueText(item.afterValue)">{{ valueText(item.afterValue) }}</span>
          </template>
        </div>
      </article>

      <yp-empty-state
        v-if="!loading && !items.length && !error"
        :title="hasFilters ? '没有符合筛选条件的动态' : '还没有单元格动态'"
        :description="hasFilters ? '调整或清除筛选条件后再试。' : '切点后的字段操作会显示在这里。'"
      />
    </div>
    <el-button v-if="nextCursor" class="cell-activity__older" :loading="loadingOlder" @click="loadOlder">加载更早动态</el-button>
  </section>
</template>

<style scoped>
.cell-activity { display: grid; gap: var(--yp-space-4); }
.cell-activity__toolbar { display: flex; align-items: center; gap: var(--yp-space-2); }
.cell-activity__toolbar svg, .cell-entry svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.cell-activity__refresh { width: 30px; min-width: 30px; height: 30px; min-height: 30px; margin-left: auto; padding: 0; border: 0 !important; border-radius: 4px; color: var(--yp-text-secondary); background: transparent; box-shadow: none !important; }
.cell-activity__refresh:hover, .cell-activity__refresh:focus-visible { color: var(--yp-text-primary); border: 0 !important; background: color-mix(in srgb, var(--yp-text-primary) 12%, var(--yp-bg-raised)); }
.cell-activity__refresh-icon { width: 15px !important; height: 15px !important; fill: currentColor !important; stroke: none !important; }
.cell-activity__filter { display: inline-flex; height: 30px; min-height: 30px; align-items: center; justify-content: center; gap: 6px; padding: 0 10px; border: 0; border-radius: var(--yp-radius-sm); color: var(--yp-text-primary); background: transparent; box-shadow: none; font: inherit; font-size: 14px; font-weight: 400; white-space: nowrap; cursor: pointer; transition: background var(--yp-motion-fast) var(--yp-ease-standard), color var(--yp-motion-fast) var(--yp-ease-standard); }
.cell-activity__filter:hover, .cell-activity__filter:focus-visible, .cell-activity__filter.is-open, .cell-activity__filter.is-active { border: 0 !important; outline: 0; color: var(--yp-action-primary); background: var(--yp-bg-hover); box-shadow: none !important; }
.cell-activity__filter-chevron { width: 14px !important; height: 14px !important; transition: transform var(--yp-motion-fast) var(--yp-ease-standard); }
.cell-activity__filter.is-open .cell-activity__filter-chevron { transform: rotate(180deg); }
.cell-activity__error { display: flex; flex-wrap: wrap; align-items: center; gap: var(--yp-space-2); }
.cell-activity__list { min-height: 180px; }
.cell-entry { display: grid; grid-template-columns: minmax(84px, .8fr) minmax(88px, 1.05fr) minmax(84px, .95fr) minmax(132px, 1.35fr); gap: 6px; align-items: center; height: 58px; padding: 0 var(--yp-space-2); overflow: hidden; border-bottom: 1px solid var(--yp-border-subtle); box-sizing: border-box; }
.cell-entry:hover { background: var(--yp-bg-hover); }
.cell-entry__time, .cell-entry__actor, .cell-entry__column, .cell-entry__change { display: inline-flex; min-width: 0; align-items: center; gap: 5px; overflow: hidden; white-space: nowrap; }
.cell-entry__time { color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); cursor: help; }
.cell-entry__actor strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--yp-type-body-size); }
.cell-entry__column { color: var(--yp-text-secondary); font-size: var(--yp-type-caption-size); text-overflow: ellipsis; }
.cell-entry__column svg { color: var(--yp-text-muted); }
.cell-entry__change { --cell-activity-label-width: clamp(52px, 42%, 112px); flex-wrap: nowrap; }
.cell-entry__change :deep(.yp-assignee) { max-width: var(--cell-activity-label-width); overflow: hidden; }
.cell-entry__change :deep(.yp-assignee__name) { text-overflow: ellipsis; }
.cell-entry__verb { color: var(--yp-status-green-foreground); font-size: var(--yp-type-caption-size); font-weight: 600; }
.cell-entry__verb--removed { color: var(--yp-status-red-foreground); }
.cell-entry__old { color: var(--yp-text-secondary); }
.cell-entry__arrow { flex: 0 0 14px; width: 14px !important; color: var(--yp-text-muted); }
.cell-entry__value { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cell-entry__label { flex: 0 1 var(--cell-activity-label-width); width: var(--cell-activity-label-width); min-width: 0; max-width: var(--cell-activity-label-width); padding: 4px 7px; overflow: hidden; border-radius: 3px; color: white; text-align: center; text-overflow: ellipsis; white-space: nowrap; font-size: var(--yp-type-caption-size); box-sizing: border-box; }
.cell-entry__label--empty { background: var(--yp-priority-empty); color: white; }
.cell-activity__older { justify-self: center; }
</style>

<style>
.cell-activity-filter-popover.el-popover { padding: 0; overflow: visible; }
.cell-activity-filter-popover .activity-filter { overflow: hidden; border-radius: var(--el-popover-border-radius, 4px); background: var(--yp-bg-raised); }
.activity-filter__header { display: flex; min-height: 62px; align-items: center; gap: 16px; padding: 0 16px; }
.activity-filter__header strong { flex: 0 0 auto; color: var(--yp-text-primary); font-size: 15px; font-weight: 600; }
.activity-filter__summary { flex: 0 1 auto; color: var(--yp-text-secondary); font-size: 13px; white-space: nowrap; }
.activity-filter__header button { flex: 0 0 auto; padding: 3px 0; border: 0; color: var(--yp-text-primary); background: transparent; font: inherit; font-size: 13px; cursor: pointer; }
.activity-filter__header button:hover:not(:disabled) { color: var(--yp-link); }
.activity-filter__header button:disabled { color: var(--yp-text-disabled); cursor: default; }
.activity-filter__columns { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); min-height: 248px; gap: 12px; padding: 12px 16px 18px; }
.activity-filter__columns section { min-width: 0; }
.activity-filter__columns h3 { margin: 0 0 10px; color: var(--yp-text-muted); font-size: 13px; font-weight: 400; }
.activity-filter__option { display: flex; width: 100%; min-height: 34px; align-items: center; justify-content: space-between; gap: 8px; margin: 0 0 6px; padding: 5px 10px; border: 0; border-radius: 0; color: var(--yp-text-primary); background: var(--yp-bg-sunken); font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.activity-filter__option:hover { background: var(--yp-bg-hover); }
.activity-filter__option.is-selected { color: var(--yp-link); background: var(--yp-bg-selected); }
.activity-filter__option > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.activity-filter__option small { flex: 0 0 auto; margin-left: auto; color: var(--yp-text-disabled); font-size: 12px; font-weight: 400; }
.activity-filter__person { display: inline-flex; align-items: center; gap: 4px; }
</style>
