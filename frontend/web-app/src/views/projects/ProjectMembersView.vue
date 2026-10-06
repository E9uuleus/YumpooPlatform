<script setup lang="ts">
import { MoreFilled, Plus, Search } from '@element-plus/icons-vue'
import {
  ProjectActorAccess,
  ProjectMembershipStatus,
  ProjectMembershipStatusFilter,
  readCsrfToken,
  type ProjectDetail,
  type ProjectMember,
  type ProjectMemberPage,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElPagination,
} from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import ProjectMemberAddDialog from '../../components/projects/ProjectMemberAddDialog.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpSegmented from '../../components/yp/YpSegmented.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import { useSession } from '../../composables/useSession'
import { formatDateOnly } from '../../design-system/dates'

type MemberAction = 'owner' | 'reactivate' | 'remove'

const ACTION_COPY: Record<MemberAction, { menu: string; prompt: (name: string) => string; done: string }> = {
  owner: { menu: '设为负责人', prompt: name => `将负责人转交给「${name}」`, done: '负责人已转交' },
  reactivate: { menu: '重新加入项目', prompt: name => `将「${name}」重新加入项目`, done: '成员已重新加入' },
  remove: { menu: '移出项目', prompt: name => `将「${name}」移出项目`, done: '成员已移出项目' },
}

const route = useRoute()
const session = useSession()
const projectId = String(route.params.projectId)
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const project = ref<ProjectDetail>()
const result = ref<ProjectMemberPage>()
const status = ref<ProjectMembershipStatusFilter>(ProjectMembershipStatusFilter.Active)
const query = ref('')
const page = ref(0)
const size = 20
const loading = ref(false)
const changing = ref<string>()
const addOpen = ref(false)
const error = ref<ApiProblem>()
const statusOptions = [
  { value: ProjectMembershipStatusFilter.Active, label: '活跃' },
  { value: ProjectMembershipStatusFilter.Removed, label: '已移出' },
  { value: ProjectMembershipStatusFilter.All, label: '全部' },
]
const canManage = computed(() => Boolean(project.value?.capabilities.canManageMembers))
const governanceReasonRequired = computed(() => project.value?.actorAccess === ProjectActorAccess.CompanyAdmin)
const searching = computed(() => Boolean(query.value.trim()))
let sequence = 0
let timer: ReturnType<typeof setTimeout> | undefined

async function loadProject(): Promise<void> {
  try {
    project.value = await projectsApi.getProject({ projectId })
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

async function loadMembers(): Promise<void> {
  const current = ++sequence
  loading.value = true
  try {
    const members = await projectsApi.listProjectMembers({
      projectId,
      status: status.value,
      ...(query.value.trim() ? { q: query.value.trim() } : {}),
      page: page.value,
      size,
    })
    if (current !== sequence) return
    if (!members.items.length && page.value > 0) {
      page.value = Math.max(0, members.totalPages - 1)
      void loadMembers()
      return
    }
    result.value = members
  } catch (reason) {
    if (current === sequence) error.value = await toApiProblem(reason)
  } finally {
    if (current === sequence) loading.value = false
  }
}

async function refresh(): Promise<void> {
  error.value = undefined
  await Promise.all([loadProject(), loadMembers()])
}

function actionsFor(member: ProjectMember): MemberAction[] {
  const capabilities = project.value?.capabilities
  if (!capabilities || member.owner) return []
  if (member.membershipStatus === ProjectMembershipStatus.Removed) {
    return capabilities.canManageMembers ? ['reactivate'] : []
  }
  return [
    ...(capabilities.canReassignOwner ? ['owner' as const] : []),
    ...(capabilities.canManageMembers ? ['remove' as const] : []),
  ]
}

async function reasonFor(action: string, required: boolean): Promise<string | null | undefined> {
  try {
    const response = await ElMessageBox.prompt(
      `${action}${required ? '；此操作必须记录治理理由。' : '；可选填理由。'}`,
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

async function run(member: ProjectMember, action: MemberAction): Promise<void> {
  const reason = await reasonFor(ACTION_COPY[action].prompt(member.displayName),
    action === 'owner' || governanceReasonRequired.value)
  if (reason === undefined) return
  const csrf = readCsrfToken()
  const detail = project.value
  if (!csrf || !detail) {
    error.value = localProblem('缺少并发或 CSRF 凭据，请刷新后重试。')
    return
  }
  changing.value = member.userId
  error.value = undefined
  const common = { projectId, xXSRFTOKEN: csrf, idempotencyKey: crypto.randomUUID() }
  try {
    if (action === 'owner') {
      await projectsApi.reassignProjectOwner({
        ...common,
        ifMatch: detail.etag,
        projectOwnerReassignmentRequest: { newOwnerUserId: member.userId, reason: reason ?? '' },
      })
    } else if (action === 'reactivate') {
      await projectsApi.addProjectMember({
        ...common,
        ifMatch: member.etag,
        projectMemberAddRequest: { userId: member.userId, reason },
      })
    } else {
      await projectsApi.removeProjectMember({
        ...common,
        userId: member.userId,
        ifMatch: member.etag,
        projectMemberRemoveRequest: { reason },
      })
    }
    ElMessage.success(ACTION_COPY[action].done)
    await refresh()
  } catch (reasonValue) {
    const problem = await toApiProblem(reasonValue)
    error.value = problem
    if (isProblemStatus(problem, 412)) await refresh()
  } finally {
    changing.value = undefined
  }
}

function changePage(next: number): void {
  page.value = next - 1
  void loadMembers()
}

function onAdded(): void {
  if (status.value === ProjectMembershipStatusFilter.Removed) status.value = ProjectMembershipStatusFilter.Active
  else void loadMembers()
}

watch(status, () => {
  page.value = 0
  void loadMembers()
})

watch(query, () => {
  clearTimeout(timer)
  timer = setTimeout(() => {
    page.value = 0
    void loadMembers()
  }, 250)
})

onMounted(() => { void refresh() })
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="project-view-stack">
    <project-workspace-header
      section="members"
      :project="project"
      title="项目成员"
    />
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <section
      class="project-members"
      aria-labelledby="project-members-title"
    >
      <header class="project-page-heading">
        <div>
          <h2 id="project-members-title">
            成员
            <span
              v-if="result && status === ProjectMembershipStatusFilter.Active && !searching"
              class="project-page-heading__count"
            >{{ result.totalElements }}</span>
          </h2>
          <p>负责人和企业管理员可以添加或移出成员，企业管理员可以转交负责人。</p>
        </div>
        <el-button
          v-if="canManage"
          type="primary"
          :icon="Plus"
          @click="addOpen = true"
        >
          添加成员
        </el-button>
      </header>
      <div class="project-members__toolbar">
        <yp-segmented
          v-model="status"
          :options="statusOptions"
          label="成员状态"
        />
        <el-input
          v-model="query"
          class="project-members__search"
          :prefix-icon="Search"
          clearable
          placeholder="搜索成员"
          aria-label="搜索成员"
        />
      </div>
      <div
        v-loading="loading"
        class="project-members__list"
      >
        <div
          class="project-members__row project-members__row--head"
          aria-hidden="true"
        >
          <span>成员</span>
          <span>角色</span>
          <span>加入时间</span>
          <span />
        </div>
        <ul
          class="project-members__rows"
          aria-label="项目成员"
        >
          <li
            v-for="member in result?.items ?? []"
            :key="member.userId"
            class="project-members__row"
            :class="{ 'is-removed': member.membershipStatus === ProjectMembershipStatus.Removed }"
          >
            <span class="project-members__person">
              <yp-assignee
                :user-id="member.userId"
                :display-name="member.displayName"
                :account-status="member.accountStatus"
                :employment-status="member.employmentStatus"
              />
              <yp-status-tag
                v-if="member.membershipStatus === ProjectMembershipStatus.Removed"
                domain="project-membership"
                :status="member.membershipStatus"
                effect="soft"
                size="small"
              />
            </span>
            <span
              class="project-members__role"
              :class="{ 'is-owner': member.owner }"
            >
              <svg
                v-if="member.owner"
                width="14"
                height="14"
                viewBox="0 0 16 16"
                fill="currentColor"
                aria-hidden="true"
              >
                <path d="M2 5.2l3.2 2.6L8 3.5l2.8 4.3L14 5.2l-1.1 6.3H3.1L2 5.2zm1.2 7.4h9.6V14H3.2v-1.4z" />
              </svg>
              {{ member.owner ? '负责人' : '成员' }}
            </span>
            <span class="project-members__joined">{{ formatDateOnly(member.joinedAt, timezone) }}</span>
            <span class="project-members__actions">
              <el-dropdown
                v-if="actionsFor(member).length"
                trigger="click"
                placement="bottom-end"
                @command="(action: string) => run(member, action as MemberAction)"
              >
                <el-button
                  text
                  :icon="MoreFilled"
                  :loading="changing === member.userId"
                  :disabled="Boolean(changing)"
                  :aria-label="`${member.displayName}的更多操作`"
                />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      v-for="(action, index) in actionsFor(member)"
                      :key="action"
                      :command="action"
                      :divided="action === 'remove' && index > 0"
                      :class="{ 'project-members__danger': action === 'remove' }"
                    >
                      {{ ACTION_COPY[action].menu }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </span>
          </li>
        </ul>
        <yp-empty-state
          v-if="!loading && result && !result.items.length"
          :reason="searching ? 'no-results' : 'empty'"
          :description="searching ? '没有匹配的成员，请调整搜索关键词。' : '当前筛选范围内暂无项目成员。'"
          compact
        />
      </div>
      <el-pagination
        v-if="result && result.totalPages > 1"
        class="project-members__pagination"
        layout="prev, pager, next"
        :current-page="page + 1"
        :page-size="size"
        :total="result.totalElements"
        @current-change="changePage"
      />
    </section>
    <project-member-add-dialog
      v-if="canManage"
      v-model="addOpen"
      :project-id="projectId"
      :project-etag="project?.etag ?? ''"
      :reason-required="governanceReasonRequired"
      :can-reassign-owner="Boolean(project?.capabilities.canReassignOwner)"
      @added="onAdded"
      @reassigned="refresh"
    />
  </div>
</template>

<style scoped>
.project-members {
  display: grid;
  gap: var(--yp-space-4);
}

.project-members__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-3);
}

.project-members__search {
  width: min(280px, 100%);
}

.project-members__list {
  min-height: 168px;
  overflow: hidden;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}

.project-members__rows {
  margin: 0;
  padding: 0;
  list-style: none;
}

.project-members__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px 120px 48px;
  align-items: center;
  gap: var(--yp-space-3);
  min-height: 56px;
  padding: 0 var(--yp-space-2) 0 var(--yp-space-4);
  border-bottom: 1px solid var(--yp-table-divider);
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard);
}

.project-members__rows .project-members__row:last-child {
  border-bottom: 0;
}

.project-members__rows .project-members__row:hover {
  background: var(--yp-table-row-hover);
}

.project-members__row--head {
  min-height: 40px;
  color: var(--yp-table-header-text);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
}

.project-members__person {
  display: flex;
  align-items: center;
  gap: var(--yp-space-2);
  min-width: 0;
}

.project-members__row.is-removed .project-members__person :deep(.yp-assignee) {
  opacity: 0.6;
}

.project-members__role {
  display: inline-flex;
  align-items: center;
  justify-self: start;
  gap: var(--yp-space-1);
  height: 24px;
  padding: 0 var(--yp-space-2);
  border-radius: var(--yp-radius-pill);
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
}

.project-members__role.is-owner {
  color: var(--yp-text-primary);
  background: color-mix(in srgb, var(--yp-status-yellow) 24%, var(--yp-bg-surface));
}

.project-members__role.is-owner svg {
  color: color-mix(in srgb, var(--yp-status-yellow) 70%, var(--yp-text-primary));
}

.project-members__joined {
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
  font-variant-numeric: tabular-nums;
}

.project-members__actions {
  display: flex;
  justify-content: flex-end;
}

.project-members__actions .el-button {
  width: 32px;
  height: 32px;
  min-height: 32px;
  padding: 0;
  color: var(--yp-text-muted);
}

.project-members__pagination {
  justify-content: center;
}

:global(.project-members__danger.el-dropdown-menu__item) {
  color: var(--yp-status-red);
}

@media (max-width: 720px) {
  .project-members__row {
    grid-template-columns: minmax(0, 1fr) auto 40px;
  }

  .project-members__row--head,
  .project-members__joined {
    display: none;
  }
}
</style>
