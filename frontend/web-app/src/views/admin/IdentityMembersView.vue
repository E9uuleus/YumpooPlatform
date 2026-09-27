<script setup lang="ts">
import {
  AccountStatus,
  EmploymentStatus,
  PlatformRoleTier,
  ManagedPlatformRole,
  readCsrfToken,
  type Member,
  type MemberPage,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElDialog,
  ElRadio,
  ElRadioGroup,
  ElAlert,
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
import { beginAuthentication } from '../../auth/navigation'
import { useSession } from '../../composables/useSession'
import { identityAdministrationApi, identityGovernanceApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpFilterBar from '../../components/yp/YpFilterBar.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import { useIdentityAdmin } from '../../composables/useIdentityAdmin'
import { businessLabel } from '../../design-system/labels'
import { getStatusPresentation } from '../../design-system/status'
import type { ActiveFilter } from '../../design-system/types'

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent

const { canWrite } = useIdentityAdmin()
const session = useSession()
const route = useRoute()
const platformRole = ref<PlatformRoleTier>()
const roleMember = ref<Member>()
const roleDialogOpen = ref(false)
const chosenTier = ref<PlatformRoleTier>(PlatformRoleTier.CompanyMember)
const roleReason = ref('')
const roleProblem = ref<ApiProblem>()
const roleLoading = ref(false)
const roleChanged = computed(() => !!roleMember.value && chosenTier.value !== tierOf(roleMember.value))
let roleAttempt: { fingerprint: string, key: string } | undefined
const tiers = [
  { value: PlatformRoleTier.CompanyMember, description: '参与所属项目，处理工作项与协作。' },
  { value: PlatformRoleTier.CompanyAdmin, description: '管理公司业务、成员账号与组织同步。' },
  { value: PlatformRoleTier.AppManager, description: '拥有公司管理能力，并可更改成员角色。' },
]
function tierOf(member: Member): PlatformRoleTier {
  return member.platformRoles.has(ManagedPlatformRole.AppManager) ? PlatformRoleTier.AppManager
    : member.platformRoles.has(ManagedPlatformRole.CompanyAdmin) ? PlatformRoleTier.CompanyAdmin : PlatformRoleTier.CompanyMember
}
function tierTagType(member: Member): 'primary' | 'info' {
  const tier = tierOf(member)
  return tier === PlatformRoleTier.CompanyMember ? 'info' : 'primary'
}
function openRole(member: Member): void {
  roleMember.value = member
  chosenTier.value = tierOf(member)
  roleReason.value = ''
  roleProblem.value = undefined
  roleAttempt = undefined
  roleDialogOpen.value = true
}
async function submitRole(): Promise<void> {
  const member = roleMember.value
  const reason = roleReason.value.trim()
  if (!member || !roleChanged.value || !reason || reason.length > 160 || roleLoading.value) return
  const csrf = readCsrfToken()
  if (!csrf) {
    roleProblem.value = localProblem('缺少 CSRF 凭据，请刷新页面后重试。')
    return
  }
  const fingerprint = JSON.stringify([member.userId, member.etag, chosenTier.value, reason])
  if (roleAttempt?.fingerprint !== fingerprint) roleAttempt = { fingerprint, key: crypto.randomUUID() }
  roleLoading.value = true
  roleProblem.value = undefined
  try {
    await identityGovernanceApi.changeMemberPlatformRole({
      userId: member.userId, ifMatch: member.etag, xXSRFTOKEN: csrf,
      idempotencyKey: roleAttempt.key,
      platformRoleChangeRequest: { role: chosenTier.value, reason },
    })
    roleDialogOpen.value = false
    ElMessage.success('成员角色已更新')
    await load()
    if (drawerOpen.value) await openMember(member.userId)
  } catch (failure) {
    const problem = await toApiProblem(failure)
    roleProblem.value = problem
    if (isProblemStatus(problem, 412)) {
      await load()
      try {
        roleMember.value = await identityAdministrationApi.getMember({ userId: member.userId })
        if (drawerOpen.value) selected.value = roleMember.value
        roleProblem.value = localProblem('成员信息已变化，请复核最新信息后重新提交。')
      } catch (refreshFailure) { roleProblem.value = await toApiProblem(refreshFailure) }
    }
  } finally { roleLoading.value = false }
}
const result = ref<MemberPage>()
const loading = ref(false)
const error = ref<ApiProblem>()
const name = ref('')
const externalUserId = ref('')
const employmentStatus = ref<EmploymentStatus>()
const accountStatus = ref<AccountStatus>()
const page = ref(0)
const size = ref(20)
const selected = ref<Member>()
const drawerOpen = ref(false)
const changingUserId = ref<string>()
const activeFilters = computed<ActiveFilter[]>(() => [
  ...(platformRole.value ? [{ key: 'platformRole', label: '角色', valueLabel: businessLabel(platformRole.value) }] : []),
  ...(name.value.trim() ? [{ key: 'name', label: '姓名', valueLabel: name.value.trim() }] : []),
  ...(externalUserId.value.trim()
    ? [{ key: 'externalUserId', label: '企微外部 ID', valueLabel: externalUserId.value.trim() }]
    : []),
  ...(employmentStatus.value
    ? [{
        key: 'employmentStatus',
        label: '就业状态',
        valueLabel: getStatusPresentation('employment', employmentStatus.value).label,
      }]
    : []),
  ...(accountStatus.value
    ? [{
        key: 'accountStatus',
        label: '账号状态',
        valueLabel: getStatusPresentation('account', accountStatus.value).label,
      }]
    : []),
])

function formatTime(value?: Date | null): string {
  return value ? value.toLocaleString('zh-CN') : '—'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    result.value = await identityAdministrationApi.listMembers({
      ...(name.value.trim() ? { name: name.value.trim() } : {}),
      ...(externalUserId.value.trim() ? { externalUserId: externalUserId.value.trim() } : {}),
      ...(employmentStatus.value ? { employmentStatus: employmentStatus.value } : {}),
      ...(accountStatus.value ? { accountStatus: accountStatus.value } : {}),
      ...(platformRole.value ? { platformRole: platformRole.value } : {}),
      page: page.value,
      size: size.value,
    })
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function openMember(userId: string): Promise<void> {
  error.value = undefined
  try {
    selected.value = await identityAdministrationApi.getMember({ userId })
    drawerOpen.value = true
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

function applyFilters(): void {
  page.value = 0
  void load()
}

function removeFilter(key: string): void {
  if (key === 'platformRole') platformRole.value = undefined
  if (key === 'name') name.value = ''
  if (key === 'externalUserId') externalUserId.value = ''
  if (key === 'employmentStatus') employmentStatus.value = undefined
  if (key === 'accountStatus') accountStatus.value = undefined
  applyFilters()
}

function clearFilters(): void {
  platformRole.value = undefined
  name.value = ''
  externalUserId.value = ''
  employmentStatus.value = undefined
  accountStatus.value = undefined
  applyFilters()
}

async function changeAccount(member: Member): Promise<void> {
  const disable = member.accountStatus === AccountStatus.Enabled
  const action = disable ? '停用' : '启用'
  const sessionImpact = disable
    ? '停用会立即撤销该成员全部登录会话。'
    : '启用不会恢复此前已撤销的登录会话。'
  let reason: string
  try {
    const response = await ElMessageBox.prompt(
      `${action}成员「${member.displayName}」。就业状态保持${getStatusPresentation('employment', member.employmentStatus).label}不变；${sessionImpact}`,
      `确认${action}账号`,
      {
        confirmButtonText: `确认${action}`,
        cancelButtonText: '取消',
        inputPlaceholder: '请输入操作理由（1～160 字）',
        inputPattern: /^(?=\s*\S)[\s\S]{1,160}$/,
        inputErrorMessage: '理由必须包含非空白字符且不超过 160 字',
        distinguishCancelAndClose: true,
      },
    )
    reason = response.value.trim()
  } catch {
    return
  }

  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新页面后重试。')
    return
  }

  changingUserId.value = member.userId
  error.value = undefined
  const parameters = {
    userId: member.userId,
    xXSRFTOKEN: csrf,
    idempotencyKey: crypto.randomUUID(),
    ifMatch: member.etag,
    governanceReasonRequest: { reason },
  }
  try {
    if (disable) {
      await identityGovernanceApi.disableMemberAccount(parameters)
    } else {
      await identityGovernanceApi.enableMemberAccount(parameters)
    }
    ElMessage.success(`账号已${action}`)
    await load()
    if (drawerOpen.value) await openMember(member.userId)
  } catch (failure) {
    const problem = await toApiProblem(failure)
    if (isProblemStatus(problem, 412)) {
      await refreshAfterConflict(member.userId, problem)
    } else {
      error.value = problem
    }
  } finally {
    changingUserId.value = undefined
  }
}

async function refreshAfterConflict(userId: string, conflict: ApiProblem): Promise<void> {
  try {
    await load()
    if (drawerOpen.value) selected.value = await identityAdministrationApi.getMember({ userId })
    error.value = conflict
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

function changeAccountById(userId: string): void {
  const member = result.value?.items.find(item => item.userId === userId)
  if (member) void changeAccount(member)
}

onMounted(load)
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
      <template #search>
        <el-input
          v-model="name"
          clearable
          placeholder="搜索成员姓名"
          aria-label="成员姓名"
          @keyup.enter="applyFilters"
        />
        <el-input
          v-model="externalUserId"
          clearable
          placeholder="企微外部 ID"
          aria-label="企微外部 ID"
          @keyup.enter="applyFilters"
        />
        <el-button
          type="primary"
          @click="applyFilters"
        >
          搜索
        </el-button>
      </template>
      <template #filters>
        <el-select
          v-model="platformRole"
          clearable
          placeholder="全部角色"
          aria-label="角色"
        >
          <el-option
            v-for="tier in tiers"
            :key="tier.value"
            :label="businessLabel(tier.value)"
            :value="tier.value"
          />
        </el-select>
        <el-select
          v-model="employmentStatus"
          clearable
          placeholder="全部就业状态"
          aria-label="就业状态"
        >
          <el-option
            label="在职"
            :value="EmploymentStatus.Active"
          />
          <el-option
            label="已离职"
            :value="EmploymentStatus.Left"
          />
        </el-select>
        <el-select
          v-model="accountStatus"
          clearable
          placeholder="全部账号状态"
          aria-label="账号状态"
        >
          <el-option
            label="已启用"
            :value="AccountStatus.Enabled"
          />
          <el-option
            label="已停用"
            :value="AccountStatus.Disabled"
          />
        </el-select>
        <el-button
          type="primary"
          @click="applyFilters"
        >
          应用
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
          label="成员"
          min-width="210"
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
          prop="externalUserId"
          label="企微外部 ID"
          min-width="180"
        />
        <el-table-column
          prop="departmentSummary"
          label="部门"
          min-width="180"
        />
        <el-table-column
          label="就业"
          width="110"
        >
          <template #default="scope">
            <yp-status-tag
              domain="employment"
              :status="scope.row.employmentStatus"
              effect="soft"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="账号"
          width="110"
        >
          <template #default="scope">
            <yp-status-tag
              domain="account"
              :status="scope.row.accountStatus"
              effect="soft"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="角色"
          min-width="180"
        >
          <template #default="scope">
            <el-tag
              :type="tierTagType(scope.row as Member)"
              :effect="tierOf(scope.row as Member) === PlatformRoleTier.AppManager ? 'dark' : 'light'"
            >
              {{ businessLabel(tierOf(scope.row as Member)) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="240"
          fixed="right"
        >
          <template #default="scope">
            <el-button
              link
              type="primary"
              @click="openMember(scope.row.userId)"
            >
              详情
            </el-button>
            <el-button
              v-if="session.canChangeMemberTier.value"
              link
              type="primary"
              :disabled="scope.row.userId === session.authentication.value?.user.id"
              :title="scope.row.userId === session.authentication.value?.user.id ? '不能更改自己的角色' : undefined"
              @click="openRole(scope.row as Member)"
            >
              更改角色
            </el-button>
            <el-button
              v-if="canWrite"
              link
              :type="scope.row.accountStatus === AccountStatus.Enabled ? 'danger' : 'success'"
              :loading="changingUserId === scope.row.userId"
              @click="changeAccountById(scope.row.userId)"
            >
              {{ scope.row.accountStatus === AccountStatus.Enabled ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <yp-empty-state
      v-else
      reason="no-results"
      description="没有符合当前筛选条件的成员。"
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

    <el-dialog
      v-model="roleDialogOpen"
      :title="`更改 ${roleMember?.displayName ?? ''} 的角色`"
      width="min(560px, 95vw)"
    >
      <el-radio-group
        v-model="chosenTier"
        class="tier-options"
        aria-label="成员角色"
      >
        <el-radio
          v-for="tier in tiers"
          :key="tier.value"
          :value="tier.value"
          border
          class="tier-card"
        >
          <strong>{{ businessLabel(tier.value) }}</strong>
          <el-tag
            v-if="roleMember && tierOf(roleMember) === tier.value"
            size="small"
            type="info"
          >
            当前
          </el-tag>
          <span class="tier-description">{{ tier.description }}</span>
        </el-radio>
      </el-radio-group>
      <el-alert
        v-if="roleChanged"
        title="该成员的登录会话将失效"
        type="warning"
        :closable="false"
      />
      <el-input
        v-model="roleReason"
        type="textarea"
        :maxlength="160"
        show-word-limit
        placeholder="请输入变更理由（1～160 字）"
        aria-label="变更理由"
      />
      <inline-problem
        v-if="roleProblem"
        :problem="roleProblem"
      />
      <el-button
        v-if="roleProblem && isProblemStatus(roleProblem, 403)"
        @click="beginAuthentication(route.fullPath)"
      >
        重新登录
      </el-button>
      <template #footer>
        <el-button @click="roleDialogOpen = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="roleLoading"
          :disabled="!roleChanged || !roleReason.trim() || roleReason.trim().length > 160"
          @click="submitRole"
        >
          {{ roleChanged ? `设为${businessLabel(chosenTier)}` : '当前角色' }}
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="drawerOpen"
      title="成员详情"
      size="min(620px, 100vw)"
    >
      <div
        v-if="selected"
        class="page-stack"
      >
        <yp-assignee
          :user-id="selected.userId"
          :display-name="selected.displayName"
          :account-status="selected.accountStatus"
          :employment-status="selected.employmentStatus"
          size="detail"
        />
        <el-descriptions
          :column="1"
          border
        >
          <el-descriptions-item label="企微外部 ID">
            {{ selected.externalUserId }}
          </el-descriptions-item>
          <el-descriptions-item label="部门">
            {{ selected.departmentSummary ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="邮箱">
            {{ selected.email ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="手机">
            {{ selected.mobile ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="就业状态">
            <yp-status-tag
              domain="employment"
              :status="selected.employmentStatus"
              effect="soft"
            />
          </el-descriptions-item>
          <el-descriptions-item label="账号状态">
            <yp-status-tag
              domain="account"
              :status="selected.accountStatus"
              effect="soft"
            />
          </el-descriptions-item>
          <el-descriptions-item label="最近同步">
            {{ formatTime(selected.directorySyncedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="角色">
            {{ businessLabel(tierOf(selected)) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-button
          v-if="canWrite"
          :type="selected.accountStatus === AccountStatus.Enabled ? 'danger' : 'success'"
          :loading="changingUserId === selected.userId"
          @click="changeAccount(selected)"
        >
          {{ selected.accountStatus === AccountStatus.Enabled ? '停用账号' : '启用账号' }}
        </el-button>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.tier-options { display: grid; gap: var(--yp-space-3); width: 100%; margin-bottom: var(--yp-space-4); }
.tier-card { margin: 0; min-height: 76px; height: auto; padding: var(--yp-space-3); }
.tier-description { display: block; color: var(--yp-text-secondary); white-space: normal; margin-top: var(--yp-space-2); }
.tier-card :deep(.el-tag) { margin-left: var(--yp-space-2); }
</style>
