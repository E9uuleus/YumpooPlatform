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
  ElButton, ElDescriptions, ElDescriptionsItem, ElDrawer, ElEmpty,
  ElPagination, ElTable, ElTableColumn, ElTag,
} from 'element-plus'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { identityAdministrationApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import { useIdentityAdmin } from '../../composables/useIdentityAdmin'

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

function formatTime(value?: Date | null): string {
  return value ? value.toLocaleString('zh-CN') : '—'
}

function statusType(value: DirectorySyncRunStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (value === DirectorySyncRunStatus.Succeeded) return 'success'
  if (value === DirectorySyncRunStatus.PartiallySucceeded) return 'warning'
  if (value === DirectorySyncRunStatus.Failed) return 'danger'
  return 'info'
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

onMounted(load)
onBeforeUnmount(clearPoll)
</script>

<template>
  <section>
    <div class="toolbar">
      <div class="filters">
        <select
          v-model="status"
          class="native-control"
          aria-label="运行状态"
          @change="applyFilters"
        >
          <option :value="undefined">
            全部状态
          </option>
          <option
            v-for="value in DirectorySyncRunStatus"
            :key="value"
            :value="value"
          >
            {{ value }}
          </option>
        </select>
        <select
          v-model="triggerType"
          class="native-control"
          aria-label="触发方式"
          @change="applyFilters"
        >
          <option :value="undefined">
            全部触发方式
          </option>
          <option
            v-for="value in DirectorySyncTriggerType"
            :key="value"
            :value="value"
          >
            {{ value }}
          </option>
        </select>
      </div>
      <el-button
        v-if="canWrite"
        type="primary"
        :loading="triggering"
        @click="triggerSync"
      >
        立即同步
      </el-button>
    </div>

    <inline-problem
      v-if="error"
      class="inline-error"
      :problem="error"
    />

    <el-table
      v-if="result?.items.length"
      v-loading="loading"
      :data="result.items"
      stripe
    >
      <el-table-column
        prop="startedAt"
        label="开始时间"
        min-width="180"
      >
        <template #default="scope">
          {{ formatTime(scope.row.startedAt) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="triggerType"
        label="触发方式"
        width="110"
      />
      <el-table-column
        prop="status"
        label="状态"
        width="180"
      >
        <template #default="scope">
          <el-tag :type="statusType(scope.row.status)">
            {{ scope.row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="发现/失败"
        width="130"
      >
        <template #default="scope">
          {{ scope.row.counts.discovered }} / {{ scope.row.counts.failed }}
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
    <el-empty
      v-else-if="!loading"
      description="暂无同步运行"
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
      size="620px"
      @closed="clearPoll"
    >
      <el-descriptions
        v-if="selected"
        :column="2"
        border
      >
        <el-descriptions-item label="状态">
          <el-tag :type="statusType(selected.status)">
            {{ selected.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="阶段">
          {{ selected.phase }}
        </el-descriptions-item>
        <el-descriptions-item label="触发方式">
          {{ selected.triggerType }}
        </el-descriptions-item>
        <el-descriptions-item label="触发人">
          {{ selected.triggeredByDisplayName ?? '系统' }}
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
          {{ selected.requestId }}
        </el-descriptions-item>
        <el-descriptions-item
          label="安全摘要"
          :span="2"
        >
          {{ selected.errorSummary ?? '无' }}
        </el-descriptions-item>
      </el-descriptions>
      <h3>失败项</h3>
      <el-table
        v-if="failures?.items.length"
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
      <el-empty
        v-else
        description="无失败项"
        :image-size="64"
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
    </el-drawer>
  </section>
</template>
