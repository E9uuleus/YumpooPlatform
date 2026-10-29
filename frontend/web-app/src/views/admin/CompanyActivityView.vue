<script setup lang="ts">
import { usePageLoad } from '../../brand/page-progress'

import { Download, Refresh } from '@element-plus/icons-vue'
import { ProjectLifecycleFilter, type CompanyActivityItem } from '@yumpoo/api-client'
import {
  ElButton,
  ElDatePicker,
  ElIcon,
  ElMessage,
  ElOption as ElOptionRaw,
  ElSelect as ElSelectRaw,
  ElTable,
  ElTableColumn,
  ElTooltip,
} from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch, type DefineComponent } from 'vue'
import { useRouter } from 'vue-router'
import { activityApi, identityAdministrationApi, projectsApi } from '../../api/client'
import { toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import {
  ACTIVITY_CATEGORIES,
  ACTIVITY_CATEGORY_ORDER,
  activityCategory,
  type ActivityCategory,
} from '../../components/collaboration/activityPresentation'
import {
  buildCompanyActivityWorkbook,
  COMPANY_ACTIVITY_EXPORT_LIMIT,
  companyActivityFileName,
} from '../../components/company/companyActivityExcel'
import { saveExportFile, XLSX_TYPE } from '../../components/dashboard/exportFile'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpFilterBar from '../../components/yp/YpFilterBar.vue'
import { useSession } from '../../composables/useSession'
import { formatTimestamp, zonedStartOfDay } from '../../design-system/dates'
import type { ActiveFilter } from '../../design-system/types'

const pageLoad = usePageLoad()
interface Option { value: string; label: string }

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const DAY = 86_400_000
const PAGE_SIZE = 50
const EXPORT_PAGE_SIZE = 100

const router = useRouter(), session = useSession()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const items = ref<CompanyActivityItem[]>([])
const nextCursor = ref<string | null>(null)
const historyStartedAt = ref<Date>()
const loading = ref(false), loadingMore = ref(false), exporting = ref(false)
const error = ref<ApiProblem>()
const dateRange = ref<[Date, Date] | null>(null)
const actorIds = ref<string[]>([]), projectIds = ref<string[]>([]), categories = ref<ActivityCategory[]>([])
const actorOptions = ref<Option[]>([]), projectOptions = ref<Option[]>([])
// Chips need labels for values picked from earlier remote searches.
const actorLabels = new Map<string, string>(), projectLabels = new Map<string, string>()
const sentinel = ref<HTMLElement>()
let requestSequence = 0
let observer: IntersectionObserver | undefined

const categoryOptions = ACTIVITY_CATEGORY_ORDER.map(key => ({ value: key, label: ACTIVITY_CATEGORIES[key].label }))
const rangeShortcuts = [
  { text: '今天', value: () => [new Date(), new Date()] },
  { text: '最近 7 天', value: () => [new Date(Date.now() - 6 * DAY), new Date()] },
  { text: '最近 30 天', value: () => [new Date(Date.now() - 29 * DAY), new Date()] },
]

const activeFilters = computed<ActiveFilter[]>(() => [
  ...(dateRange.value
    ? [{ key: 'date', label: '操作时间', valueLabel: `${dayLabel(dateRange.value[0])} 至 ${dayLabel(dateRange.value[1])}` }]
    : []),
  ...actorIds.value.map(id => ({ key: `actor:${id}`, label: '操作人', valueLabel: `操作人：${actorLabels.get(id) ?? id}` })),
  ...projectIds.value.map(id => ({ key: `project:${id}`, label: '项目', valueLabel: `项目：${projectLabels.get(id) ?? id}` })),
  ...categories.value.map(key => ({ key: `category:${key}`, label: '分类', valueLabel: `分类：${ACTIVITY_CATEGORIES[key].label}` })),
])

function dayLabel(value: Date): string {
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
}

function nextDay(value: Date): Date {
  return new Date(value.getFullYear(), value.getMonth(), value.getDate() + 1)
}

function criteria() {
  const eventTypes = categories.value.flatMap(key => ACTIVITY_CATEGORIES[key].eventTypes)
  return {
    ...(eventTypes.length ? { eventType: new Set(eventTypes) } : {}),
    ...(actorIds.value.length ? { actorUserId: new Set(actorIds.value) } : {}),
    ...(projectIds.value.length ? { projectId: new Set(projectIds.value) } : {}),
    ...(dateRange.value
      ? {
          occurredFrom: zonedStartOfDay(dateRange.value[0], timezone.value),
          occurredTo: new Date(zonedStartOfDay(nextDay(dateRange.value[1]), timezone.value).getTime() - 1),
        }
      : {}),
  }
}

function categoryView(item: CompanyActivityItem) {
  return ACTIVITY_CATEGORIES[activityCategory(item.activity.sourceEventType)]
}

function rowKey(item: CompanyActivityItem): string {
  return item.activity.id
}

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadingMore.value = false
  error.value = undefined
  try {
    const page = await activityApi.listCompanyActivity({ ...criteria(), size: PAGE_SIZE })
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

async function loadMore(): Promise<void> {
  if (!nextCursor.value || loading.value || loadingMore.value) return
  const sequence = requestSequence
  loadingMore.value = true
  try {
    const page = await activityApi.listCompanyActivity({ ...criteria(), size: PAGE_SIZE, cursor: nextCursor.value })
    if (sequence !== requestSequence) return
    items.value = [...items.value, ...page.items]
    nextCursor.value = page.nextCursor
  } catch (reason) {
    if (sequence === requestSequence) error.value = await toApiProblem(reason)
  } finally {
    if (sequence === requestSequence) loadingMore.value = false
  }
}

const quiet = () => undefined

function searchActors(query: string): void {
  void identityAdministrationApi.listMembers({ ...(query.trim() ? { name: query.trim() } : {}), page: 0, size: 20 })
    .then(page => {
      page.items.forEach(member => actorLabels.set(member.userId, member.displayName))
      actorOptions.value = page.items.map(member => ({ value: member.userId, label: member.displayName }))
    }, quiet)
}

// No actorAccesses: for COMPANY_ADMIN the server searches every project, archived ones included.
function searchProjects(query: string): void {
  void projectsApi.listProjects({ lifecycle: ProjectLifecycleFilter.All, ...(query.trim() ? { query: query.trim() } : {}), page: 0, size: 20 })
    .then(page => {
      page.items.forEach(project => projectLabels.set(project.id, project.name))
      projectOptions.value = page.items.map(project => ({ value: project.id, label: project.name }))
    }, quiet)
}

function removeFilter(key: string): void {
  const [kind, value] = key.split(':')
  if (kind === 'date') dateRange.value = null
  if (kind === 'actor') actorIds.value = actorIds.value.filter(id => id !== value)
  if (kind === 'project') projectIds.value = projectIds.value.filter(id => id !== value)
  if (kind === 'category') categories.value = categories.value.filter(category => category !== value)
}

function clearFilters(): void {
  dateRange.value = null
  actorIds.value = []
  projectIds.value = []
  categories.value = []
}

function openProject(item: CompanyActivityItem): void {
  void router.push({ name: 'project-activity', params: { projectId: item.projectId } })
}

async function exportExcel(): Promise<void> {
  if (exporting.value) return
  exporting.value = true
  // Snapshot the filters: the cursor is bound to them, and they may change while pages load.
  const query = criteria()
  const filterSummary = activeFilters.value.map(filter => filter.valueLabel).join('；') || '全部'
  try {
    const rows: CompanyActivityItem[] = []
    let cursor: string | null = null
    do {
      const page = await activityApi.listCompanyActivity({ ...query, size: EXPORT_PAGE_SIZE, ...(cursor ? { cursor } : {}) })
      rows.push(...page.items)
      cursor = page.nextCursor
    } while (cursor && rows.length < COMPANY_ACTIVITY_EXPORT_LIMIT)
    const truncated = Boolean(cursor) || rows.length > COMPANY_ACTIVITY_EXPORT_LIMIT
    const exported = rows.slice(0, COMPANY_ACTIVITY_EXPORT_LIMIT)
    const blob = await buildCompanyActivityWorkbook({
      rows: exported.map(item => ({
        occurredAt: formatTimestamp(item.activity.occurredAt, timezone.value),
        actor: item.activity.actor.displayName,
        projectName: item.projectName ?? '已删除项目',
        category: categoryView(item).label,
        content: item.activity.summary,
        entityRef: item.activity.entityRef ?? '',
      })),
      timezone: timezone.value,
      filterSummary,
      exportedAt: formatTimestamp(new Date(), timezone.value),
      truncated,
    })
    if (await saveExportFile(blob, companyActivityFileName(), XLSX_TYPE)) {
      ElMessage.success(truncated
        ? `已导出最近 ${COMPANY_ACTIVITY_EXPORT_LIMIT} 条，缩小筛选范围可导出更早记录`
        : `已导出 ${exported.length} 条活动`)
    }
  } catch (reason) {
    ElMessage.error(reason instanceof Error && reason.message ? reason.message : '导出失败，请重试')
  } finally {
    exporting.value = false
  }
}

watch([dateRange, actorIds, projectIds, categories], () => void load())

onMounted(() => {
  void pageLoad.track(() => load())
  searchActors('')
  searchProjects('')
  if (typeof IntersectionObserver === 'undefined' || !sentinel.value) return
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) void loadMore()
  }, { rootMargin: '240px' })
  observer.observe(sentinel.value)
})

onBeforeUnmount(() => {
  requestSequence++
  observer?.disconnect()
})
</script>

<template>
  <section class="company-activity">
    <yp-filter-bar
      :filters="activeFilters"
      :loading="loading"
      :popover-width="560"
      labeled-tools
      @remove="removeFilter"
      @clear="clearFilters"
    >
      <template #filters>
        <div class="company-activity-filter company-activity-filter--wide">
          <span>操作时间</span>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            unlink-panels
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            range-separator="至"
            :shortcuts="rangeShortcuts"
            aria-label="操作时间"
          />
        </div>
        <div class="company-activity-filter">
          <span>操作人</span>
          <el-select
            v-model="actorIds"
            multiple
            collapse-tags
            filterable
            remote
            :remote-method="searchActors"
            placeholder="搜索成员"
            aria-label="操作人"
          >
            <el-option
              v-for="option in actorOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
        <div class="company-activity-filter">
          <span>项目</span>
          <el-select
            v-model="projectIds"
            multiple
            collapse-tags
            filterable
            remote
            :remote-method="searchProjects"
            placeholder="搜索项目"
            aria-label="项目"
          >
            <el-option
              v-for="option in projectOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
        <div class="company-activity-filter">
          <span>分类</span>
          <el-select
            v-model="categories"
            multiple
            collapse-tags
            placeholder="全部分类"
            aria-label="分类"
          >
            <el-option
              v-for="option in categoryOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
      </template>
      <template #actions>
        <el-tooltip
          content="刷新"
          placement="top"
        >
          <el-button
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新活动日志"
            @click="load"
          />
        </el-tooltip>
        <el-button
          :icon="Download"
          :loading="exporting"
          :disabled="loading || !items.length"
          @click="exportExcel"
        >
          导出 Excel
        </el-button>
      </template>
    </yp-filter-bar>

    <inline-problem
      v-if="error"
      :problem="error"
      title="活动日志加载失败"
    />

    <div class="table-surface company-activity__surface">
      <div
        v-if="loading || items.length"
        class="table-scroll"
      >
        <el-table
          v-loading="loading"
          :data="items"
          :row-key="rowKey"
        >
          <el-table-column
            label="操作时间"
            width="160"
          >
            <template #default="scope">
              <span class="numeric">{{ formatTimestamp(scope.row.activity.occurredAt, timezone) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="操作人"
            min-width="140"
          >
            <template #default="scope">
              <yp-assignee
                v-if="scope.row.activity.actor.userId"
                :user-id="scope.row.activity.actor.userId"
                :display-name="scope.row.activity.actor.displayName"
                size="table"
              />
              <span v-else>{{ scope.row.activity.actor.displayName }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="项目名称"
            min-width="180"
          >
            <template #default="scope">
              <el-button
                v-if="scope.row.projectName"
                link
                type="primary"
                @click="openProject(scope.row as CompanyActivityItem)"
              >
                {{ scope.row.projectName }}
              </el-button>
              <span
                v-else
                class="muted-text"
              >已删除项目</span>
            </template>
          </el-table-column>
          <el-table-column
            label="分类"
            width="110"
          >
            <template #default="scope">
              <span
                class="company-activity__category"
                :style="{ '--activity-tone': categoryView(scope.row as CompanyActivityItem).tone }"
              >
                <el-icon aria-hidden="true">
                  <component :is="categoryView(scope.row as CompanyActivityItem).icon" />
                </el-icon>
                {{ categoryView(scope.row as CompanyActivityItem).label }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            label="具体内容"
            min-width="360"
          >
            <template #default="scope">
              <span class="company-activity__summary">{{ scope.row.activity.summary }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <yp-empty-state
        v-else
        :reason="activeFilters.length ? 'no-results' : 'empty'"
        :title="activeFilters.length ? '没有匹配的活动' : '还没有活动'"
        :description="activeFilters.length ? '调整时间、操作人、项目或分类后再试。' : '各项目中的操作会汇总显示在这里。'"
        compact
      />
      <footer class="company-activity__footer">
        <div
          ref="sentinel"
          aria-hidden="true"
        />
        <el-button
          v-if="nextCursor"
          text
          :loading="loadingMore"
          @click="loadMore"
        >
          加载更多
        </el-button>
        <span v-else-if="items.length">已显示全部 {{ items.length }} 条</span>
        <span v-if="historyStartedAt">
          活动从 {{ formatTimestamp(historyStartedAt, timezone) }} 开始记录，更早历史未回填。
        </span>
      </footer>
    </div>
  </section>
</template>

<style scoped>
.company-activity {
  display: grid;
  gap: var(--yp-space-4);
}

.company-activity .yp-filter-bar {
  margin-bottom: 0;
}

.company-activity-filter {
  display: grid;
  min-width: 0;
  gap: 6px;
}

.company-activity-filter--wide {
  grid-column: 1 / -1;
}

.company-activity-filter > span {
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
}

.company-activity-filter .el-select,
.company-activity-filter :deep(.el-date-editor) {
  width: 100%;
}

.company-activity__surface {
  border-color: var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}

.company-activity__surface :deep(.el-table__cell) {
  padding-block: 10px;
}

.company-activity__category {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--activity-tone);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
}

.company-activity__summary {
  color: var(--yp-text-primary);
  overflow-wrap: anywhere;
}

.company-activity__footer {
  display: grid;
  justify-items: center;
  gap: var(--yp-space-2);
  padding: var(--yp-space-3) var(--yp-space-4) var(--yp-space-4);
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}
</style>
