<script setup lang="ts">
import { ProjectActorAccess, ProjectLifecycleFilter, type ProjectPage, type ProjectSummary } from '@yumpoo/api-client'
import { ElButton, ElInput, ElPagination, ElTable, ElTableColumn } from 'element-plus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { projectsApi } from '../../api/client'
import { toApiProblem, type ApiProblem } from '../../api/problems'
import { vBrandLoading as vLoading } from '../../brand/loading'
import InlineProblem from '../../components/InlineProblem.vue'
import ArchivedProjectTable from '../../components/projects/ArchivedProjectTable.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpTabs, { type TabItem } from '../../components/yp/YpTabs.vue'
import { useSession } from '../../composables/useSession'
import { formatTimestamp } from '../../design-system/dates'

const ACCESS_LABELS: Partial<Record<ProjectActorAccess, string>> = {
  [ProjectActorAccess.Owner]: '负责人', [ProjectActorAccess.Member]: '成员', [ProjectActorAccess.CompanyAdmin]: '未加入',
}
const tabs: TabItem[] = [{ value: 'active', label: '进行中' }, { value: 'archived', label: '已归档' }]
const router = useRouter(), session = useSession()
const tab = ref('active')
const result = ref<ProjectPage>(), error = ref<ApiProblem>(), query = ref(''), page = ref(0), loading = ref(false), size = 20
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'UTC')
let searchTimer: ReturnType<typeof setTimeout> | undefined, request = 0

// No actorAccesses: for COMPANY_ADMIN the server returns every project in the company.
async function load(): Promise<void> {
  const sequence = ++request
  loading.value = true
  error.value = undefined
  try {
    const next = await projectsApi.listProjects({
      lifecycle: ProjectLifecycleFilter.Active, page: page.value, size,
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
onBeforeUnmount(() => { request++; if (searchTimer) clearTimeout(searchTimer) })
// Restoring in the archived tab changes this list, so it reloads whenever the tab is shown again.
watch(tab, value => { if (value === 'active') void load() })
void load()
</script>

<template>
  <section class="company-projects">
    <yp-tabs
      v-model="tab"
      :items="tabs"
      label="公司项目"
    />
    <div v-if="tab === 'active'">
      <inline-problem
        v-if="error"
        :problem="error"
      />
      <div class="project-list-surface">
        <div class="company-projects__toolbar">
          <el-input
            v-model="query"
            clearable
            aria-label="搜索项目名称或编号"
            placeholder="搜索项目名称或编号"
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
              label="我的参与"
              width="110"
            >
              <template #default="scope">
                {{ ACCESS_LABELS[(scope.row as ProjectSummary).actorAccess] }}
              </template>
            </el-table-column>
            <el-table-column
              label="最后修改时间"
              min-width="190"
            >
              <template #default="scope">
                {{ formatTimestamp(scope.row.updatedAt, timezone) }}
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
          :description="query ? '没有符合搜索条件的项目。' : '公司暂无进行中的项目。'"
          compact
        />
      </div>
    </div>
    <archived-project-table v-else />
  </section>
</template>

<style scoped>
.company-projects { display: grid; gap: var(--yp-space-4); }
.company-projects__toolbar { display: flex; gap: var(--yp-space-3); padding: var(--yp-space-4); }
.company-projects__toolbar .el-input { max-width: 360px; }
</style>
