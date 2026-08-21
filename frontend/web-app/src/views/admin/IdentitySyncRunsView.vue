<script setup lang="ts">
import {
  DirectorySyncRunStatus,
  DirectorySyncTriggerType,
  readCsrfToken,
  type DirectorySyncFailurePage,
  type DirectorySyncRun,
  type DirectorySyncRunPage,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElOption as ElOptionRaw,
  ElPagination,
  ElSelect as ElSelectRaw,
  ElTable,
  ElTableColumn,
} from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, type DefineComponent } from 'vue'
import { identityAdministrationApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpFilterBar from '../../components/yp/YpFilterBar.vue'
import YpProgress from '../../components/yp/YpProgress.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import { useIdentityAdmin } from '../../composables/useIdentityAdmin'
import { businessLabel } from '../../design-system/labels'
import type { ActiveFilter } from '../../design-system/types'

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const statusOptions = [
  DirectorySyncRunStatus.Running,
  DirectorySyncRunStatus.Succeeded,
  DirectorySyncRunStatus.PartiallySucceeded,
  DirectorySyncRunStatus.Failed,
]

const { canWrite } = useIdentityAdmin()
const result = ref<DirectorySyncRunPage>()
const loading = ref(false)
const error = ref<ApiProblem>()
const status = ref<DirectorySyncRunStatus>()
const triggerType = ref<DirectorySyncTriggerType>()
const page = ref(0)
const size = ref(20)
const selected = ref<DirectorySyncRun>()
const drawerOpen = ref(false)
const failures = ref<DirectorySyncFailurePage>()
const failurePage = ref(0)
const triggering = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | undefined

const activeFilters = computed<ActiveFilter[]>(() => [
  ...(status.value
    ? [{ key: 'status', label: '运行状态', valueLabel: statusLabel(status.value) }]
    : []),
  ...(triggerType.value
    ? [{ key: 'triggerType', label: '触发方式', valueLabel: businessLabel(triggerType.value) }]
    : []),
])

function statusLabel(value: DirectorySyncRunStatus): string {
  return {
    [DirectorySyncRunStatus.Running]: '运行中',
    [DirectorySyncRunStatus.Succeeded]: '成功',
    [DirectorySyncRunStatus.PartiallySucceeded]: '部分成功',
    [DirectorySyncRunStatus.Failed]: '失败',
    [DirectorySyncRunStatus.UnknownDefaultOpenApi]: `未知（${value}）`,
  }[value]
}

function formatTime(value?: Date | null): string {
  return value ? value.toLocaleString('zh-CN') : '—'
}

function progressState(value: DirectorySyncRunStatus): 'default' | 'complete' | 'blocked' {
  if (value === DirectorySyncRunStatus.Succeeded) return 'complete'
  if (value === DirectorySyncRunStatus.Failed) return 'blocked'
  return 'default'
}

function successfulCount(value: unknown): number {
  const run = value as DirectorySyncRun
  const counts = run.counts
  return counts.created + counts.updated + counts.unchanged + counts.left + counts.returned
}

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    result.value = await identityAdministrationApi.listDirectorySyncRuns({
      ...(status.value ? { status: status.value } : {}),
      ...(triggerType.value ? { triggerType: triggerType.value } : {}),
      page: page.value,
      size: size.value,
    })
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function loadFailures(runId: string): Promise<void> {
  failures.value = await identityAdministrationApi.listDirectorySyncFailures({
    runId,
    page: failurePage.value,
    size: 20,
  })
}

async function openRun(runId: string): Promise<void> {
  clearPoll()
  error.value = undefined
  try {
    selected.value = await identityAdministrationApi.getDirectorySyncRun({ runId })
    drawerOpen.value = true
    failurePage.value = 0
    await loadFailures(runId)
    if (selected.value.status === DirectorySyncRunStatus.Running) schedulePoll(runId)
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

function schedulePoll(runId: string): void {
  pollTimer = setTimeout(async () => {
    await openRun(runId)
    await load()
  }, 2000)
}

function clearPoll(): void {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = undefined
}

async function triggerSync(): Promise<void> {
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新页面后重试。')
    return
  }
  triggering.value = true
  error.value = undefined
  try {
    const run = await identityAdministrationApi.triggerDirectorySync({
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
    })
    await load()
    await openRun(run.runId)
  } catch (reason) {
    const problem = await toApiProblem(reason)
    if (isProblemStatus(problem, 409) && problem.kind === 'response' && problem.location) {
      const runId = syncRunIdFromLocation(problem.location)
      if (runId) {
        await openRun(runId)
        return
      }
    }
    error.value = problem
  } finally {
    triggering.value = false
  }
}

function syncRunIdFromLocation(location: string): string | undefined {
  try {
    const parsed = new URL(location, window.location.origin)
    if (parsed.origin !== window.location.origin || parsed.search || parsed.hash) return undefined
    const match = parsed.pathname.match(/^\/api\/v1\/admin\/directory-sync-runs\/([0-9a-f-]+)$/i)
    return match?.[1]
  } catch {
    return undefined
  }
}

function applyFilters(): void {
  page.value = 0
  void load()
}

function removeFilter(key: string): void {
  if (key === 'status') status.value = undefined
  if (key === 'triggerType') triggerType.value = undefined
  applyFilters()
}

function clearFilters(): void {
  status.value = undefined
  triggerType.value = undefined
  applyFilters()
}

onMounted(load)
onBeforeUnmount(clearPoll)
</script>

<template>
  <section>
    <yp-filter-bar
      :filters="activeFilters"
      :result-count="result?.totalElements"
      :loading="loading"
      @remove="removeFilter"
      @clear="clearFilters"
    >
      <template #filters>
        <el-select
          v-model="status"
          clearable
          placeholder="全部状态"
          aria-label="运行状态"
          @change="applyFilters"
        >
          <el-option
            v-for="value in statusOptions"
            :key="value"
            :label="statusLabel(value)"
            :value="value"
          />
        </el-select>
        <el-select
          v-model="triggerType"
          clearable
          placeholder="全部触发方式"
          aria-label="触发方式"
          @change="applyFilters"
        >
          <el-option
            label="手动"
            :value="DirectorySyncTriggerType.Manual"
          />
          <el-option
            label="计划任务"
            :value="DirectorySyncTriggerType.Scheduled"
          />
        </el-select>
      </template>
      <template #actions>
        <el-button
          v-if="canWrite"
          type="primary"
          :loading="triggering"
          @click="triggerSync"
        >
          立即同步
        </el-button>
      </template>
    </yp-filter-bar>

    <inline-problem
      v-if="error"
      :problem="error"
    />

    <div
      v-if="loading || result?.items.length"
      class="table-surface table-scroll"
    >
      <el-table
        v-loading="loading"
        :data="result?.items ?? []"
      >
        <el-table-column
          label="开始时间"
          min-width="180"
        >
          <template #default="scope">
            <span class="numeric">{{ formatTime(scope.row.startedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="触发方式"
          width="120"
        >
          <template #default="scope">
            {{ businessLabel(scope.row.triggerType) }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="150"
        >
          <template #default="scope">
            <yp-status-tag
              domain="directory-sync"
              :status="scope.row.status"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="处理进度"
          min-width="220"
        >
          <template #default="scope">
            <yp-progress
              :value="successfulCount(scope.row)"
              :max="scope.row.counts.discovered"
              :state="progressState(scope.row.status)"
            />
          </template>
        </el-table-column>
        <el-table-column
          prop="errorCode"
          label="错误码"
          min-width="170"
        />
        <el-table-column
          label="操作"
          width="90"
          fixed="right"
        >
          <template #default="scope">
            <el-button
              link
              type="primary"
              @click="openRun(scope.row.runId)"
            >
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <yp-empty-state
      v-else
      description="暂无通讯录同步运行。"
    />

    <el-pagination
      v-if="result && result.totalElements > 0"
      class="page-control"
      layout="total, sizes, prev, pager, next"
      :total="result.totalElements"
      :current-page="page + 1"
      :page-size="size"
      :page-sizes="[20, 50, 100]"
      @update:current-page="value => { page = value - 1; load() }"
      @update:page-size="value => { size = value; page = 0; load() }"
    />

    <el-drawer
      v-model="drawerOpen"
      title="同步运行详情"
      size="min(680px, 100vw)"
      @closed="clearPoll"
    >
      <div
        v-if="selected"
        class="page-stack"
      >
        <div class="header-meta">
          <yp-status-tag
            domain="directory-sync"
            :status="selected.status"
          />
          <yp-assignee
            :user-id="selected.triggeredByUserId"
            :display-name="selected.triggeredByDisplayName || '系统任务'"
          />
        </div>
        <yp-progress
          :value="successfulCount(selected)"
          :max="selected.counts.discovered"
          :state="progressState(selected.status)"
        />
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="阶段">
            {{ businessLabel(selected.phase) }}
          </el-descriptions-item>
          <el-descriptions-item label="触发方式">
            {{ businessLabel(selected.triggerType) }}
          </el-descriptions-item>
          <el-descriptions-item label="开始时间">
            {{ formatTime(selected.startedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="结束时间">
            {{ formatTime(selected.finishedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="错误码">
            {{ selected.errorCode ?? '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="requestId">
            <code>{{ selected.requestId }}</code>
          </el-descriptions-item>
          <el-descriptions-item
            label="安全摘要"
            :span="2"
          >
            {{ selected.errorSummary ?? '无' }}
          </el-descriptions-item>
        </el-descriptions>
        <section>
          <h3 class="section-heading">
            失败项
          </h3>
          <div
            v-if="failures?.items.length"
            class="table-surface table-scroll"
          >
            <el-table
              :data="failures.items"
              size="small"
            >
              <el-table-column
                prop="maskedMemberReference"
                label="成员引用"
              />
              <el-table-column
                prop="result"
                label="结果"
                width="120"
              />
              <el-table-column
                prop="errorCode"
                label="错误码"
                min-width="180"
              />
            </el-table>
          </div>
          <yp-empty-state
            v-else
            title="无失败项"
            description="当前同步运行没有记录失败成员。"
          />
          <el-pagination
            v-if="failures && failures.totalElements > 20"
            small
            layout="prev, pager, next"
            :total="failures.totalElements"
            :page-size="20"
            :current-page="failurePage + 1"
            @update:current-page="value => { failurePage = value - 1; if (selected) loadFailures(selected.runId) }"
          />
        </section>
      </div>
    </el-drawer>
  </section>
</template>
