<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import {
  readCsrfToken,
  type ArchivedWorkItem,
  type ArchivedWorkItemPage,
  type WorkItemStatusLabel,
} from '@yumpoo/api-client'
import { ElButton, ElDrawer, ElInput, ElMessage, ElPagination, ElTable, ElTableColumn } from 'element-plus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workItemsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { useSession } from '../../composables/useSession'
import { formatTimestamp } from '../../design-system/dates'
import InlineProblem from '../InlineProblem.vue'
import YpEmptyState from '../yp/YpEmptyState.vue'
import { workItemLabelColorStyle } from './workItemLabelColors'

const props = defineProps<{
  modelValue: boolean
  projectId: string
  statuses: readonly WorkItemStatusLabel[]
  /** 项目进行中且本人是负责人或成员，与归档的权限一致。 */
  canRestore: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  open: [workItemId: string]
  restored: [workItemIds: string[]]
}>()

const SIZE = 20
const session = useSession()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const query = ref('')
const page = ref(0)
const result = ref<ArchivedWorkItemPage>()
const loading = ref(false)
const restoring = ref(false)
const selected = ref<ArchivedWorkItem[]>([])
const problem = ref<ApiProblem>()
const statusByCode = computed(() => new Map(props.statuses.map(status => [status.code, status])))
let sequence = 0
let timer: ReturnType<typeof setTimeout> | undefined

async function load(): Promise<void> {
  const current = ++sequence
  loading.value = true
  try {
    const next = await workItemsApi.listArchivedWorkItems({
      projectId: props.projectId,
      page: page.value,
      size: SIZE,
      ...(query.value.trim() ? { q: query.value.trim() } : {}),
    })
    if (current !== sequence) return
    if (!next.items.length && page.value > 0) {
      page.value = Math.max(0, next.totalPages - 1)
      void load()
      return
    }
    result.value = next
    selected.value = []
  } catch (reason) {
    if (current === sequence) problem.value = await toApiProblem(reason)
  } finally {
    if (current === sequence) loading.value = false
  }
}

/** 逐项调用取消归档，失败不阻止其他项，与表格批量操作一样串行执行。 */
async function restore(items: readonly ArchivedWorkItem[]): Promise<void> {
  const csrf = readCsrfToken()
  if (!csrf) {
    problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  restoring.value = true
  problem.value = undefined
  const restored: string[] = []
  for (const item of items) {
    try {
      await workItemsApi.unarchiveWorkItem({ workItemId: item.id, xXSRFTOKEN: csrf, ifMatch: item.etag,
        idempotencyKey: crypto.randomUUID() })
      restored.push(item.id)
    } catch (reason) {
      problem.value = await toApiProblem(reason)
    }
  }
  restoring.value = false
  if (restored.length) {
    ElMessage.success(restored.length === items.length
      ? `已恢复 ${restored.length} 项`
      : `已恢复 ${restored.length} 项，${items.length - restored.length} 项失败`)
    emit('restored', restored)
  }
  await load()
}

function changePage(next: number): void {
  page.value = next - 1
  void load()
}

const statusStyle = (code: string): Record<string, string> =>
  ({ ...workItemLabelColorStyle(statusByCode.value.get(code)?.colorToken), color: 'var(--yp-text-inverse)' })

watch(() => props.modelValue, open => {
  if (!open) return
  query.value = ''
  page.value = 0
  problem.value = undefined
  void load()
})
watch(query, () => {
  clearTimeout(timer)
  timer = setTimeout(() => {
    page.value = 0
    void load()
  }, 250)
})
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="已归档工作项"
    size="min(720px, 95vw)"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="archived__intro">
      归档的工作项不在表格和看板中显示，恢复后回到原来的位置；子工作项随父项一起隐藏和恢复。
    </p>
    <inline-problem
      v-if="problem"
      :problem="problem"
    />
    <div class="archived__toolbar">
      <el-input
        v-model="query"
        class="archived__search"
        :prefix-icon="Search"
        clearable
        placeholder="搜索名称或编号"
        aria-label="搜索已归档工作项"
      />
      <el-button
        v-if="canRestore"
        type="primary"
        :disabled="!selected.length"
        :loading="restoring"
        @click="restore(selected)"
      >
        恢复所选{{ selected.length ? ` ${selected.length} 项` : '' }}
      </el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="result?.items ?? []"
      row-key="id"
      @selection-change="selected = $event"
    >
      <el-table-column
        v-if="canRestore"
        type="selection"
        width="40"
      />
      <el-table-column
        label="工作项"
        min-width="240"
      >
        <template #default="{ row }">
          <button
            type="button"
            class="archived__title"
            @click="emit('open', row.id)"
          >
            <span class="archived__no">{{ row.itemNo }}</span>{{ row.title }}
          </button>
          <small
            v-if="row.parentTitle"
            class="archived__meta"
          >父项：{{ row.parentTitle }}</small>
          <small
            v-else-if="row.subitemCount"
            class="archived__meta"
          >含 {{ row.subitemCount }} 个子工作项</small>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="112"
      >
        <template #default="{ row }">
          <span
            class="archived__status"
            :style="statusStyle(row.statusCode)"
          >{{ statusByCode.get(row.statusCode)?.displayName ?? row.statusCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="归档时间"
        width="168"
      >
        <template #default="{ row }">
          <span class="archived__time">{{ formatTimestamp(row.updatedAt, timezone) }}</span>
          <small class="archived__meta">{{ row.updatedByDisplayName }}</small>
        </template>
      </el-table-column>
      <el-table-column
        v-if="canRestore"
        width="76"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="restoring"
            @click="restore([row as ArchivedWorkItem])"
          >
            恢复
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <yp-empty-state
          compact
          :reason="query.trim() ? 'no-results' : 'empty'"
          :description="query.trim() ? '没有匹配的已归档工作项。' : '本项目没有已归档的工作项。'"
        />
      </template>
    </el-table>
    <el-pagination
      v-if="result && result.totalPages > 1"
      class="archived__pagination"
      layout="prev, pager, next"
      :current-page="page + 1"
      :page-size="SIZE"
      :total="result.totalElements"
      @current-change="changePage"
    />
  </el-drawer>
</template>

<style scoped>
.archived__intro { margin: 0 0 var(--yp-space-3); color: var(--yp-text-secondary); font-size: 13px; }
.archived__toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--yp-space-3); margin-bottom: var(--yp-space-3); }
.archived__search { width: min(280px, 100%); }
.archived__title { display: block; max-width: 100%; overflow: hidden; padding: 0; border: 0; background: transparent; color: var(--yp-text-primary); font: inherit; text-align: left; white-space: nowrap; text-overflow: ellipsis; cursor: pointer; }
.archived__title:hover { color: var(--yp-action-primary); }
.archived__no { margin-right: var(--yp-space-2); color: var(--yp-text-muted); font-variant-numeric: tabular-nums; }
.archived__meta { display: block; color: var(--yp-text-muted); font-size: 12px; }
.archived__status { display: inline-block; padding: 2px 8px; border-radius: var(--yp-radius-pill); font-size: 12px; }
.archived__time { font-variant-numeric: tabular-nums; }
.archived__pagination { justify-content: center; margin-top: var(--yp-space-3); }
</style>
