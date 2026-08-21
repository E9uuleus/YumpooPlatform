<script setup lang="ts">
import {
  ProjectActorAccess,
  ProjectMembershipStatusFilter,
  readCsrfToken,
  type ProjectDetail,
  type ProjectMember,
  type ProjectMemberCandidate,
  type ProjectMemberPage,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption as ElOptionRaw,
  ElPagination,
  ElSelect as ElSelectRaw,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { computed, onMounted, ref, type DefineComponent } from 'vue'
import { useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpFilterBar from '../../components/yp/YpFilterBar.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import type { ActiveFilter } from '../../design-system/types'
import ProjectWorkspaceHeader from './ProjectWorkspaceHeader.vue'

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent

const route = useRoute()
const projectId = String(route.params.projectId)
const project = ref<ProjectDetail>()
const result = ref<ProjectMemberPage>()
const candidates = ref<ProjectMemberCandidate[]>([])
const status = ref(ProjectMembershipStatusFilter.All)
const name = ref('')
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const changing = ref<string>()
const error = ref<ApiProblem>()
const governanceReasonRequired = computed(() => project.value?.actorAccess === ProjectActorAccess.CompanyAdmin)
const activeFilters = computed<ActiveFilter[]>(() => [
  ...(status.value !== ProjectMembershipStatusFilter.All
    ? [{
        key: 'status',
        label: '成员状态',
        valueLabel: status.value === ProjectMembershipStatusFilter.Active ? '活跃' : '已移除',
      }]
    : []),
  ...(name.value.trim()
    ? [{ key: 'name', label: '候选人', valueLabel: name.value.trim() }]
    : []),
])

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    const [detail, members] = await Promise.all([
      projectsApi.getProject({ projectId }),
      projectsApi.listProjectMembers({
        projectId,
        status: status.value,
        page: page.value,
        size: size.value,
      }),
    ])
    project.value = detail
    result.value = members
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function searchCandidates(): Promise<void> {
  if (!name.value.trim()) {
    candidates.value = []
    return
  }
  try {
    candidates.value = (await projectsApi.listProjectMemberCandidates({
      projectId,
      name: name.value.trim(),
      page: 0,
      size: 20,
    })).items
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

async function reasonFor(
  action: string,
  required = governanceReasonRequired.value,
): Promise<string | null | undefined> {
  try {
    const response = await ElMessageBox.prompt(
      `${action}${required ? '；管理员治理路径必须记录理由。' : '；可选填理由。'}`,
      action,
      {
        inputPlaceholder: required ? '请输入 10～500 字治理理由' : '可选理由',
        inputPattern: required ? /^(?=[\s\S]{10,500}$)(?=\s*\S)/ : /^(?:|(?=\s*\S)[\s\S]{1,500})$/,
        inputErrorMessage: required ? '治理理由必须为 10～500 字' : '理由不得超过 500 字',
      },
    )
    return response.value.trim() || null
  } catch {
    return undefined
  }
}

async function add(candidate: ProjectMemberCandidate): Promise<void> {
  const reason = await reasonFor(candidate.membershipStatus === 'REMOVED' ? '重激活成员' : '加入成员')
  if (reason === undefined) return
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  changing.value = candidate.userId
  try {
    await projectsApi.addProjectMember({
      projectId,
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      projectMemberAddRequest: { userId: candidate.userId, reason },
      ...(candidate.membershipEtag ? { ifMatch: candidate.membershipEtag } : {}),
    })
    ElMessage.success(candidate.membershipStatus === 'REMOVED' ? '成员已重激活' : '成员已加入')
    candidates.value = []
    name.value = ''
    await load()
  } catch (reasonValue) {
    error.value = await toApiProblem(reasonValue)
    await refreshOnConflict(error.value)
  } finally {
    changing.value = undefined
  }
}

async function remove(member: ProjectMember): Promise<void> {
  const reason = await reasonFor(`移除成员「${member.displayName}」`)
  if (reason === undefined) return
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  changing.value = member.userId
  try {
    await projectsApi.removeProjectMember({
      projectId,
      userId: member.userId,
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      ifMatch: member.etag,
      projectMemberRemoveRequest: { reason },
    })
    ElMessage.success('成员已移除')
    await load()
  } catch (reasonValue) {
    error.value = await toApiProblem(reasonValue)
    await refreshOnConflict(error.value)
  } finally {
    changing.value = undefined
  }
}

async function reassign(candidate: ProjectMemberCandidate): Promise<void> {
  const reason = await reasonFor(`将负责人重指派为「${candidate.displayName}」`, true)
  if (!reason) return
  const csrf = readCsrfToken()
  if (!csrf || !project.value) {
    error.value = localProblem('缺少并发或 CSRF 凭据，请刷新后重试。')
    return
  }
  changing.value = candidate.userId
  try {
    await projectsApi.reassignProjectOwner({
      projectId,
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      ifMatch: project.value.etag,
      projectOwnerReassignmentRequest: { newOwnerUserId: candidate.userId, reason },
    })
    ElMessage.success('负责人已重指派')
    candidates.value = []
    name.value = ''
    await load()
  } catch (reasonValue) {
    error.value = await toApiProblem(reasonValue)
    await refreshOnConflict(error.value)
  } finally {
    changing.value = undefined
  }
}

async function refreshOnConflict(problem?: ApiProblem): Promise<void> {
  if (problem && isProblemStatus(problem, 412)) await load()
}

function applyStatus(): void {
  page.value = 0
  void load()
}

function removeFilter(key: string): void {
  if (key === 'status') {
    status.value = ProjectMembershipStatusFilter.All
    applyStatus()
  }
  if (key === 'name') {
    name.value = ''
    candidates.value = []
  }
}

function clearFilters(): void {
  status.value = ProjectMembershipStatusFilter.All
  name.value = ''
  candidates.value = []
  applyStatus()
}

function removeRow(row: unknown): void {
  void remove(row as ProjectMember)
}

onMounted(load)
</script>

<template>
  <div class="project-view-stack">
    <project-workspace-header
      section="members"
      :project="project"
      title="项目成员"
      description="负责人执行日常管理，企业管理员执行治理与负责人重指派。"
    />
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <section class="project-content-surface">
      <div class="project-section-heading">
        <div>
          <h2>成员</h2>
          <p>查看成员状态，并在权限允许时加入、移除或重指派负责人。</p>
        </div>
      </div>
      <yp-filter-bar
        :filters="activeFilters"
        :result-count="result?.totalElements"
        :loading="loading"
        labeled-tools
        popover-class="project-filter-popover"
        @remove="removeFilter"
        @clear="clearFilters"
      >
        <template #search>
          <el-input
            v-model="name"
            clearable
            placeholder="搜索可加入的成员"
            aria-label="候选成员姓名"
            @keyup.enter="searchCandidates"
            @clear="candidates = []"
          />
          <el-button
            :disabled="!project?.capabilities.canManageMembers"
            @click="searchCandidates"
          >
            搜索
          </el-button>
        </template>
        <template #filters>
          <el-select
            v-model="status"
            aria-label="成员状态"
            @change="applyStatus"
          >
            <el-option
              label="全部成员"
              :value="ProjectMembershipStatusFilter.All"
            />
            <el-option
              label="活跃"
              :value="ProjectMembershipStatusFilter.Active"
            />
            <el-option
              label="已移除"
              :value="ProjectMembershipStatusFilter.Removed"
            />
          </el-select>
        </template>
      </yp-filter-bar>

      <div
        v-if="candidates.length"
        class="candidate-panel"
      >
        <strong>候选人</strong>
        <div
          v-for="candidate in candidates"
          :key="candidate.userId"
          class="candidate-row"
        >
          <div class="header-meta">
            <yp-assignee
              :user-id="candidate.userId"
              :display-name="candidate.displayName"
              :account-status="candidate.accountStatus"
              :employment-status="candidate.employmentStatus"
            />
            <yp-status-tag
              v-if="candidate.membershipStatus"
              domain="project-membership"
              :status="candidate.membershipStatus"
              effect="soft"
              size="small"
            />
          </div>
          <div class="candidate-actions">
            <el-button
              size="small"
              :loading="changing === candidate.userId"
              :disabled="candidate.owner || candidate.membershipStatus === 'ACTIVE'"
              @click="add(candidate)"
            >
              加入 / 重激活
            </el-button>
            <el-button
              v-if="project?.capabilities.canReassignOwner"
              size="small"
              @click="reassign(candidate)"
            >
              设为负责人
            </el-button>
          </div>
        </div>
      </div>

      <div
        v-if="loading || result?.items.length"
        class="table-surface table-scroll"
      >
        <el-table
          v-loading="loading"
          :data="result?.items ?? []"
        >
          <el-table-column
            label="成员"
            min-width="220"
          >
            <template #default="scope">
              <yp-assignee
                :user-id="scope.row.userId"
                :display-name="scope.row.displayName"
                :account-status="scope.row.accountStatus"
                :employment-status="scope.row.employmentStatus"
                size="table"
              />
            </template>
          </el-table-column>
          <el-table-column
            label="角色"
            width="120"
          >
            <template #default="scope">
              <el-tag
                v-if="scope.row.owner"
                type="warning"
                effect="plain"
              >
                负责人
              </el-tag>
              <span v-else>成员</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="120"
          >
            <template #default="scope">
              <yp-status-tag
                domain="project-membership"
                :status="scope.row.membershipStatus"
                effect="soft"
              />
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="130"
          >
            <template #default="scope">
              <el-button
                v-if="scope.row.membershipStatus === 'ACTIVE' && !scope.row.owner"
                link
                type="danger"
                :loading="changing === scope.row.userId"
                :disabled="!project?.capabilities.canManageMembers"
                @click="removeRow(scope.row)"
              >
                移除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <yp-empty-state
        v-else
        description="当前筛选范围内暂无项目成员。"
        compact
      />
      <el-pagination
        v-if="result && result.totalElements > 0"
        class="page-control"
        layout="prev, pager, next, total"
        :current-page="page + 1"
        :page-size="size"
        :total="result.totalElements"
        @current-change="next => { page = next - 1; load() }"
      />
    </section>
  </div>
</template>
