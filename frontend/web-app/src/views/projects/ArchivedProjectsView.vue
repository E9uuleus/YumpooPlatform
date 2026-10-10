<script setup lang="ts">
import { ProjectLifecycleFilter, readCsrfToken, type ProjectPage, type ProjectSummary } from '@yumpoo/api-client'
import { ElButton, ElInput, ElMessage, ElMessageBox, ElPagination, ElTable, ElTableColumn } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { projectsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import { vBrandLoading as vLoading } from '../../brand/loading'
import { notifyProjectLifecycleChanged } from '../../composables/projectLifecycleEvents'
import { useSession } from '../../composables/useSession'
import { formatTimestamp } from '../../design-system/dates'

const router = useRouter(), session = useSession()
const result = ref<ProjectPage>(), error = ref<ApiProblem>(), query = ref(''), page = ref(0)
const loading = ref(false), busyProjectId = ref(''), size = 20
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'UTC')
let searchTimer: ReturnType<typeof setTimeout> | undefined, request = 0

async function load(): Promise<void> {
  const sequence = ++request
  loading.value = true
  error.value = undefined
  try {
    const next = await projectsApi.listProjects({
      lifecycle: ProjectLifecycleFilter.Archived, page: page.value, size,
      ...(query.value.trim() ? { query: query.value.trim() } : {}),
    })
    if (sequence === request) result.value = next
  } catch (reason) {
    const problem = await toApiProblem(reason)
    if (sequence === request) error.value = problem
  } finally { if (sequence === request) loading.value = false }
}
function search(): void {
  if (searchTimer) clearTimeout(searchTimer)
  request++
  searchTimer = setTimeout(() => { page.value = 0; void load() }, 300)
}
function open(project: ProjectSummary): void {
  void router.push({ name: 'project-overview', params: { projectId: project.id } })
}
async function restore(project: ProjectSummary): Promise<void> {
  if (busyProjectId.value) return
  try {
    await ElMessageBox.confirm(`恢复“${project.name}”后，成员重新可见并计入统计。`, '恢复项目', {
      type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消',
    })
  } catch { return }
  const token = readCsrfToken()
  if (!token) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  busyProjectId.value = project.id
  try {
    await projectsApi.restoreProject({
      projectId: project.id, ifMatch: project.etag, xXSRFTOKEN: token, idempotencyKey: crypto.randomUUID(),
    })
    ElMessage.success('项目已恢复')
    notifyProjectLifecycleChanged(project.id)
    if (result.value?.items.length === 1 && page.value > 0) page.value--
    await load()
  } catch (reason) {
    const problem = await toApiProblem(reason)
    if (isProblemStatus(problem, 412)) { await load(); ElMessage.warning('项目已更新，请根据最新状态重试。') }
    error.value = problem
  } finally { busyProjectId.value = '' }
}
onMounted(load)
onBeforeUnmount(() => { request++; if (searchTimer) clearTimeout(searchTimer) })
</script>

<template>
  <section class="project-catalog archived-projects">
    <project-workspace-header
      section="catalog"
      title="归档项目"
      description="仅展示你负责的归档项目；企业管理员可查看本公司全部归档项目。归档项目不计入统计。"
    />
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <div class="project-list-surface">
      <div class="archived-projects__toolbar">
        <el-input
          v-model="query"
          clearable
          aria-label="搜索归档项目名称或编号"
          placeholder="搜索归档项目名称或编号"
          @input="search"
          @clear="search"
        />
        <el-button
          :loading="loading"
          @click="load"
        >
          刷新
        </el-button>
      </div>
      <div
        v-if="loading || result?.items.length"
        v-loading="loading"
        class="table-scroll"
        :aria-busy="loading"
      >
        <el-table
          class="project-management-table"
          :data="result?.items ?? []"
        >
          <el-table-column
            label="项目名称"
            min-width="230"
          >
            <template #default="scope">
              <el-button
                link
                type="primary"
                @click="open(scope.row as ProjectSummary)"
              >
                {{ scope.row.name }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column
            prop="code"
            label="项目编号"
            width="120"
          />
          <el-table-column
            label="负责人"
            min-width="150"
          >
            <template #default="scope">
              <yp-assignee
                :user-id="scope.row.ownerUserId"
                :display-name="scope.row.ownerDisplayName"
                size="table"
              />
            </template>
          </el-table-column>
          <el-table-column
            label="归档时间"
            min-width="190"
          >
            <template #default="scope">
              {{ scope.row.archivedAt ? formatTimestamp(scope.row.archivedAt, timezone) : '—' }}
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="170"
            fixed="right"
          >
            <template #default="scope">
              <el-button
                link
                @click="open(scope.row as ProjectSummary)"
              >
                打开
              </el-button>
              <el-button
                v-if="scope.row.capabilities.canRestore"
                link
                type="primary"
                :loading="busyProjectId === scope.row.id"
                :disabled="Boolean(busyProjectId) && busyProjectId !== scope.row.id"
                @click="restore(scope.row as ProjectSummary)"
              >
                恢复
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="result && result.totalElements > 0"
          class="page-control"
          layout="prev, pager, next, total"
          :current-page="page + 1"
          :page-size="size"
          :total="result.totalElements"
          @current-change="next => { page = next - 1; load() }"
        />
      </div>
      <yp-empty-state
        v-else
        :reason="query ? 'no-results' : 'empty'"
        :description="query ? '没有符合搜索条件的归档项目。' : '暂无可访问的归档项目。归档后，可在这里只读浏览或恢复。'"
        compact
      />
    </div>
  </section>
</template>

<style scoped>
.archived-projects__toolbar {
  display: flex;
  gap: var(--yp-space-3);
  padding: var(--yp-space-4);
}
.archived-projects__toolbar .el-input { max-width: 360px; }
</style>
