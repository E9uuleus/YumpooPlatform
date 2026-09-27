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
import { CircleCheckFilled, Key, OfficeBuilding, Search, User } from '@element-plus/icons-vue'
import {
  ElButton,
  ElDrawer,
  ElDialog,
  ElIcon,
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
} from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, type Component, type DefineComponent } from 'vue'
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
import { formatTimestamp } from '../../design-system/dates'
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
function tierMeta(tier: PlatformRoleTier): { icon: Component, tone: string, description: string } {
  const description = tiers.find(entry => entry.value === tier)?.description ?? ''
  if (tier === PlatformRoleTier.AppManager) return { icon: Key, tone: 'manager', description }
  if (tier === PlatformRoleTier.CompanyAdmin) return { icon: OfficeBuilding, tone: 'admin', description }
  return { icon: User, tone: 'member', description }
}
function isSelf(member: Member): boolean {
  return member.userId === session.authentication.value?.user.id
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
let searchTimer: ReturnType<typeof setTimeout> | undefined
const activeFilters = computed<ActiveFilter[]>(() => [
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
  ...(externalUserId.value.trim()
    ? [{ key: 'externalUserId', label: '企微外部 ID', valueLabel: externalUserId.value.trim() }]
    : []),
])
const hasCriteria = computed(() => !!platformRole.value || !!name.value.trim() || activeFilters.value.length > 0)
const timezone = computed(() => session.authentication.value?.company.timezone || 'UTC')

function formatTime(value?: Date | null): string {
  return value ? formatTimestamp(value, timezone.value) : '—'
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

function selectTier(tier?: PlatformRoleTier): void {
  if (platformRole.value === tier) return
  platformRole.value = tier
  applyFilters()
}

function scheduleSearch(): void {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(searchNow, 350)
}

function searchNow(): void {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = undefined
  applyFilters()
}

function removeFilter(key: string): void {
  if (key === 'externalUserId') externalUserId.value = ''
  if (key === 'employmentStatus') employmentStatus.value = undefined
  if (key === 'accountStatus') accountStatus.value = undefined
  applyFilters()
}

function clearFilters(): void {
  externalUserId.value = ''
  employmentStatus.value = undefined
  accountStatus.value = undefined
  applyFilters()
}

function resetAll(): void {
  if (searchTimer) clearTimeout(searchTimer)
  platformRole.value = undefined
  name.value = ''
  clearFilters()
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

onMounted(load)
onBeforeUnmount(() => { if (searchTimer) clearTimeout(searchTimer) })
</script>

<template>
  <section class="members-view">
    <nav
      class="tier-tabs"
      aria-label="按角色筛选"
    >
      <button
        type="button"
        class="tier-tab"
        :class="{ active: !platformRole }"
        :aria-pressed="!platformRole"
        @click="selectTier(undefined)"
      >
        全部成员
      </button>
      <button
        v-for="tier in tiers"
        :key="tier.value"
        type="button"
        class="tier-tab"
        :class="[`tier--${tierMeta(tier.value).tone}`, { active: platformRole === tier.value }]"
        :aria-pressed="platformRole === tier.value"
        @click="selectTier(tier.value)"
      >
        <el-icon aria-hidden="true">
          <component :is="tierMeta(tier.value).icon" />
        </el-icon>{{ businessLabel(tier.value) }}
      </button>
    </nav>

    <yp-filter-bar
      class="members-filter"
      :filters="activeFilters"
      :result-count="result?.totalElements"
      :loading="loading"
      :popover-width="520"
      labeled-tools
      @remove="removeFilter"
      @clear="clearFilters"
    >
      <template #filters>
        <div class="member-filter-field">
          <span>就业状态</span>
          <el-select
            v-model="employmentStatus"
            clearable
            placeholder="全部"
            aria-label="就业状态"
            @change="applyFilters"
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
        </div>
        <div class="member-filter-field">
          <span>账号状态</span>
          <el-select
            v-model="accountStatus"
            clearable
            placeholder="全部"
            aria-label="账号状态"
            @change="applyFilters"
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
        </div>
        <div class="member-filter-field">
          <span>企微外部 ID</span>
          <el-input
            v-model="externalUserId"
            clearable
            placeholder="输入后按回车"
            aria-label="企微外部 ID"
            @keyup.enter="applyFilters"
            @clear="applyFilters"
          />
        </div>
      </template>
      <template #actions>
        <el-input
          v-model="name"
          class="members-search"
          clearable
          :prefix-icon="Search"
          placeholder="搜索成员姓名"
          aria-label="成员姓名"
          @input="scheduleSearch"
          @keyup.enter="searchNow"
          @clear="searchNow"
        />
      </template>
    </yp-filter-bar>

    <inline-problem
      v-if="error"
      :problem="error"
    />

    <div
      v-if="loading || result?.items.length"
      class="table-surface table-scroll members-table"
    >
      <el-table
        v-loading="loading"
        :data="result?.items ?? []"
        row-key="userId"
      >
        <el-table-column
          label="成员"
          min-width="220"
        >
          <template #default="scope">
            <div class="member-cell">
              <yp-assignee
                :user-id="scope.row.userId"
                :display-name="scope.row.displayName"
                :show-name="false"
                tooltip-disabled
              />
              <div class="member-cell__text">
                <span class="member-cell__name">
                  <span class="member-cell__display">{{ scope.row.displayName }}</span><span
                    v-if="isSelf(scope.row as Member)"
                    class="self-chip"
                  >你</span>
                </span>
                <span class="member-cell__id">{{ scope.row.externalUserId }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="部门"
          min-width="140"
          show-overflow-tooltip
        >
          <template #default="scope">
            <span v-if="scope.row.departmentSummary">{{ scope.row.departmentSummary }}</span>
            <span
              v-else
              class="muted-text"
            >—</span>
          </template>
        </el-table-column>
        <el-table-column
          label="角色"
          width="150"
        >
          <template #default="scope">
            <span
              class="tier-badge"
              :class="`tier--${tierMeta(tierOf(scope.row as Member)).tone}`"
            >
              <el-icon aria-hidden="true">
                <component :is="tierMeta(tierOf(scope.row as Member)).icon" />
              </el-icon>{{ businessLabel(tierOf(scope.row as Member)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="140"
        >
          <template #default="scope">
            <div class="status-cell">
              <yp-status-tag
                domain="account"
                :status="scope.row.accountStatus"
                effect="soft"
                size="small"
              />
              <yp-status-tag
                v-if="scope.row.employmentStatus !== EmploymentStatus.Active"
                domain="employment"
                :status="scope.row.employmentStatus"
                effect="soft"
                size="small"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="170"
          align="right"
        >
          <template #default="scope">
            <div class="row-actions">
              <el-button
                text
                size="small"
                @click="openMember(scope.row.userId)"
              >
                详情
              </el-button>
              <el-button
                v-if="session.canChangeMemberTier.value"
                text
                size="small"
                :disabled="isSelf(scope.row as Member)"
                :title="isSelf(scope.row as Member) ? '不能更改自己的角色' : undefined"
                @click="openRole(scope.row as Member)"
              >
                更改角色
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <yp-empty-state
      v-else-if="result"
      :reason="hasCriteria ? 'no-results' : 'empty'"
      :title="hasCriteria ? null : '暂无成员'"
      :description="hasCriteria ? '没有符合当前条件的成员。' : '企业微信通讯录同步完成后，成员会显示在这里。'"
    >
      <template
        v-if="hasCriteria"
        #action
      >
        <el-button @click="resetAll">
          清除筛选
        </el-button>
      </template>
    </yp-empty-state>

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
      append-to-body
    >
      <div class="role-dialog">
        <div
          v-if="roleMember"
          class="role-dialog__member"
        >
          <yp-assignee
            :user-id="roleMember.userId"
            :display-name="roleMember.displayName"
            :show-name="false"
            tooltip-disabled
          />
          <div class="member-cell__text">
            <span class="member-cell__name">{{ roleMember.displayName }}</span>
            <span class="member-cell__id">{{ roleMember.externalUserId }}</span>
          </div>
          <span
            class="tier-badge"
            :class="`tier--${tierMeta(tierOf(roleMember)).tone}`"
          >
            <el-icon aria-hidden="true">
              <component :is="tierMeta(tierOf(roleMember)).icon" />
            </el-icon>{{ businessLabel(tierOf(roleMember)) }}
          </span>
        </div>
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
            class="tier-option"
            :class="`tier--${tierMeta(tier.value).tone}`"
          >
            <span
              class="tier-option__icon"
              aria-hidden="true"
            >
              <el-icon><component :is="tierMeta(tier.value).icon" /></el-icon>
            </span>
            <span class="tier-option__text">
              <span class="tier-option__title">
                <strong>{{ businessLabel(tier.value) }}</strong><span
                  v-if="roleMember && tierOf(roleMember) === tier.value"
                  class="tier-option__current"
                >当前</span>
              </span>
              <span class="tier-option__description">{{ tier.description }}</span>
            </span>
            <el-icon
              class="tier-option__check"
              aria-hidden="true"
            >
              <circle-check-filled />
            </el-icon>
          </el-radio>
        </el-radio-group>
        <el-alert
          v-if="roleChanged"
          title="该成员的登录会话将失效"
          description="保存后对方需要重新登录，新角色才会生效。"
          type="warning"
          show-icon
          :closable="false"
        />
        <div class="role-dialog__field">
          <label for="role-change-reason">变更理由<span aria-hidden="true">*</span></label>
          <el-input
            id="role-change-reason"
            v-model="roleReason"
            type="textarea"
            :rows="3"
            :maxlength="160"
            show-word-limit
            placeholder="请输入变更理由（1～160 字）"
            aria-label="变更理由"
          />
        </div>
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
      </div>
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
      size="min(520px, 100vw)"
      append-to-body
    >
      <div
        v-if="selected"
        class="member-profile"
      >
        <header class="member-profile__hero">
          <span class="member-profile__avatar">
            <yp-assignee
              :user-id="selected.userId"
              :display-name="selected.displayName"
              :show-name="false"
              size="detail"
              tooltip-disabled
            />
          </span>
          <div class="member-profile__identity">
            <h3>
              {{ selected.displayName }}<span
                v-if="isSelf(selected)"
                class="self-chip"
              >你</span>
            </h3>
            <span class="member-cell__id">{{ selected.externalUserId }}</span>
            <div class="member-profile__badges">
              <span
                class="tier-badge"
                :class="`tier--${tierMeta(tierOf(selected)).tone}`"
              >
                <el-icon aria-hidden="true">
                  <component :is="tierMeta(tierOf(selected)).icon" />
                </el-icon>{{ businessLabel(tierOf(selected)) }}
              </span>
              <yp-status-tag
                domain="account"
                :status="selected.accountStatus"
                effect="soft"
                size="small"
              />
              <yp-status-tag
                domain="employment"
                :status="selected.employmentStatus"
                effect="soft"
                size="small"
              />
            </div>
          </div>
        </header>

        <section class="member-profile__section">
          <h4>联系方式</h4>
          <dl>
            <div>
              <dt>邮箱</dt>
              <dd>{{ selected.email ?? '—' }}</dd>
            </div>
            <div>
              <dt>手机</dt>
              <dd>{{ selected.mobile ?? '—' }}</dd>
            </div>
          </dl>
        </section>

        <section class="member-profile__section">
          <h4>组织</h4>
          <dl>
            <div>
              <dt>部门</dt>
              <dd>{{ selected.departmentSummary ?? '—' }}</dd>
            </div>
            <div>
              <dt>企微外部 ID</dt>
              <dd class="member-profile__mono">
                {{ selected.externalUserId }}
              </dd>
            </div>
            <div>
              <dt>最近同步</dt>
              <dd>{{ formatTime(selected.directorySyncedAt) }}</dd>
            </div>
          </dl>
        </section>

        <section class="member-profile__section">
          <h4>角色</h4>
          <div
            class="member-profile__role"
            :class="`tier--${tierMeta(tierOf(selected)).tone}`"
          >
            <span
              class="tier-option__icon"
              aria-hidden="true"
            >
              <el-icon><component :is="tierMeta(tierOf(selected)).icon" /></el-icon>
            </span>
            <div class="member-profile__role-text">
              <strong>{{ businessLabel(tierOf(selected)) }}</strong>
              <p>{{ tierMeta(tierOf(selected)).description }}</p>
            </div>
            <el-button
              v-if="session.canChangeMemberTier.value && !isSelf(selected)"
              size="small"
              @click="openRole(selected)"
            >
              更改角色
            </el-button>
          </div>
        </section>

        <section
          v-if="canWrite"
          class="member-profile__access"
          :class="{ 'is-disabled': selected.accountStatus !== AccountStatus.Enabled }"
        >
          <div>
            <strong>账号访问</strong>
            <p>
              {{ selected.accountStatus === AccountStatus.Enabled
                ? '停用会立即撤销该成员全部登录会话，就业状态保持不变。'
                : '启用后成员可重新登录，此前已撤销的会话不会恢复。' }}
            </p>
          </div>
          <el-button
            plain
            :type="selected.accountStatus === AccountStatus.Enabled ? 'danger' : 'success'"
            :loading="changingUserId === selected.userId"
            @click="changeAccount(selected)"
          >
            {{ selected.accountStatus === AccountStatus.Enabled ? '停用账号' : '启用账号' }}
          </el-button>
        </section>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.tier--member { --tier-color: var(--yp-status-gray); }
.tier--admin { --tier-color: var(--yp-status-blue); }
.tier--manager { --tier-color: var(--yp-status-purple); }

.tier-tabs {
  display: flex;
  gap: var(--yp-space-1);
  overflow-x: auto;
  border-bottom: 1px solid var(--yp-border-subtle);
  scrollbar-width: none;
}
.tier-tab {
  position: relative;
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  height: 40px;
  padding: 0 var(--yp-space-3);
  border: 0;
  border-radius: var(--yp-radius-sm) var(--yp-radius-sm) 0 0;
  color: var(--yp-text-secondary);
  background: transparent;
  font-size: var(--yp-type-body-size);
  font-weight: 500;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard), background-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.tier-tab .el-icon { color: color-mix(in srgb, var(--tier-color) 80%, var(--yp-text-primary)); font-size: 15px; }
.tier-tab:hover { color: var(--yp-text-primary); background: var(--yp-bg-hover); }
.tier-tab:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: -2px; }
.tier-tab.active { color: var(--yp-text-primary); font-weight: 600; }
.tier-tab.active::after {
  position: absolute;
  right: var(--yp-space-2);
  bottom: -1px;
  left: var(--yp-space-2);
  height: 2px;
  border-radius: 2px;
  background: var(--yp-action-primary);
  content: "";
}

.members-view .members-filter { margin-bottom: var(--yp-space-3); border-bottom: 0; }
.members-view .members-filter :deep(.yp-filter-bar__tool.labeled .yp-filter-bar__badge) {
  position: static;
  color: var(--yp-status-blue-foreground);
  background: var(--yp-action-primary);
}
.members-search { width: min(260px, 100%); }
.member-filter-field { display: grid; min-width: 0; gap: 6px; }
.member-filter-field > span { color: var(--yp-text-secondary); font-size: var(--yp-type-caption-size); font-weight: 600; }
.member-filter-field .el-select,
.member-filter-field .el-input { width: 100%; }

.members-table :deep(.el-table__cell) { padding-block: 10px; }
.member-cell { display: flex; min-width: 0; align-items: center; gap: var(--yp-space-3); }
.member-cell__text { display: grid; min-width: 0; line-height: 1.4; }
.member-cell__name { display: flex; min-width: 0; align-items: center; gap: 6px; color: var(--yp-text-primary); font-weight: 600; }
.member-cell__display { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.member-cell__id {
  overflow: hidden;
  color: var(--yp-text-muted);
  font-family: var(--yp-font-mono);
  font-size: var(--yp-type-caption-size);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.self-chip {
  flex: none;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--yp-radius-pill);
  color: var(--yp-link);
  background: var(--yp-bg-selected);
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
}
.status-cell { display: flex; flex-wrap: wrap; gap: 6px; }
.row-actions { display: flex; justify-content: flex-end; gap: 2px; }
.row-actions .el-button { color: var(--yp-text-secondary); }
.row-actions .el-button + .el-button { margin-left: 0; }
.row-actions .el-button:not(.is-disabled):hover,
.row-actions .el-button:not(.is-disabled):focus-visible { color: var(--yp-link); background: var(--yp-bg-selected); }
.row-actions .el-button.is-disabled { color: var(--yp-text-disabled); }

.tier-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 24px;
  padding: 0 var(--yp-space-2);
  border-radius: var(--yp-radius-sm);
  color: color-mix(in srgb, var(--tier-color) 78%, var(--yp-text-primary));
  background: color-mix(in srgb, var(--tier-color) 13%, var(--yp-bg-surface));
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
  white-space: nowrap;
}
.tier-badge .el-icon { font-size: 13px; }

.role-dialog { display: grid; gap: var(--yp-space-4); }
.role-dialog__member {
  display: flex;
  align-items: center;
  gap: var(--yp-space-3);
  padding: var(--yp-space-3) var(--yp-space-4);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-sunken);
}
.role-dialog__member .member-cell__text { flex: 1; }
.tier-options { display: grid; gap: var(--yp-space-2); width: 100%; }
.tier-options :deep(.tier-option.el-radio) {
  display: flex;
  height: auto;
  margin: 0;
  padding: var(--yp-space-3) var(--yp-space-4);
  border-color: var(--yp-border-default);
  border-radius: var(--yp-radius-md);
  white-space: normal;
  transition: border-color var(--yp-motion-fast) var(--yp-ease-standard), background-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.tier-options :deep(.tier-option.el-radio:hover) { border-color: var(--yp-border-strong); }
.tier-options :deep(.tier-option.el-radio.is-checked) {
  border-color: var(--yp-action-primary);
  background: color-mix(in srgb, var(--yp-bg-selected) 70%, transparent);
}
.tier-options :deep(.tier-option:has(.el-radio__original:focus-visible)) { outline: 2px solid var(--yp-focus-ring); outline-offset: 2px; }
.tier-options :deep(.tier-option .el-radio__input) { position: absolute; opacity: 0; pointer-events: none; }
.tier-options :deep(.tier-option .el-radio__label) {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  gap: var(--yp-space-3);
  padding: 0;
  color: var(--yp-text-primary);
}
.tier-option__icon {
  display: grid;
  width: 36px;
  height: 36px;
  flex: none;
  place-items: center;
  border-radius: var(--yp-radius-md);
  color: color-mix(in srgb, var(--tier-color) 80%, var(--yp-text-primary));
  background: color-mix(in srgb, var(--tier-color) 14%, var(--yp-bg-surface));
  font-size: 18px;
}
.tier-option__text { display: grid; flex: 1; min-width: 0; gap: 2px; }
.tier-option__title { display: flex; align-items: center; gap: var(--yp-space-2); }
.tier-option__title strong { font-size: var(--yp-type-body-size); font-weight: 600; }
.tier-option__current {
  height: 18px;
  padding: 0 6px;
  border-radius: var(--yp-radius-pill);
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
}
.tier-option__description { color: var(--yp-text-secondary); font-size: var(--yp-type-caption-size); line-height: var(--yp-type-caption-line); }
.tier-option__check { flex: none; color: var(--yp-action-primary); font-size: 20px; opacity: 0; transition: opacity var(--yp-motion-fast) var(--yp-ease-standard); }
.tier-option.is-checked .tier-option__check { opacity: 1; }
.role-dialog__field { display: grid; gap: 6px; }
.role-dialog__field label { color: var(--yp-text-primary); font-size: 13px; font-weight: 600; }
.role-dialog__field label span { margin-left: 2px; color: var(--yp-status-red); }

.member-profile { display: grid; gap: var(--yp-space-6); }
.member-profile__hero {
  display: flex;
  align-items: center;
  gap: var(--yp-space-4);
  padding-bottom: var(--yp-space-5);
  border-bottom: 1px solid var(--yp-border-subtle);
}
.member-profile__avatar :deep(.el-avatar) { width: 56px; height: 56px; font-size: 18px; }
.member-profile__identity { display: grid; min-width: 0; gap: 2px; }
.member-profile__identity h3 {
  display: flex;
  align-items: center;
  gap: var(--yp-space-2);
  margin: 0;
  color: var(--yp-text-primary);
  font-size: 18px;
  font-weight: 600;
  line-height: 26px;
}
.member-profile__badges { display: flex; flex-wrap: wrap; gap: 6px; margin-top: var(--yp-space-2); }
.member-profile__section h4 {
  margin: 0 0 var(--yp-space-2);
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
}
.member-profile dl {
  display: grid;
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
}
.member-profile dl > div { display: grid; grid-template-columns: 104px minmax(0, 1fr); gap: var(--yp-space-3); padding: 10px 14px; }
.member-profile dl > div + div { border-top: 1px solid var(--yp-border-subtle); }
.member-profile dt { color: var(--yp-text-secondary); }
.member-profile dd { margin: 0; color: var(--yp-text-primary); overflow-wrap: anywhere; }
.member-profile__mono { font-family: var(--yp-font-mono); font-size: 13px; }
.member-profile__role {
  display: flex;
  align-items: center;
  gap: var(--yp-space-3);
  padding: var(--yp-space-3) 14px;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
}
.member-profile__role-text { flex: 1; min-width: 0; }
.member-profile__role-text strong { color: var(--yp-text-primary); font-weight: 600; }
.member-profile__role-text p { margin: 2px 0 0; color: var(--yp-text-secondary); font-size: var(--yp-type-caption-size); line-height: var(--yp-type-caption-line); }
.member-profile__access {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--yp-space-4);
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--yp-status-red) 32%, var(--yp-border-subtle));
  border-radius: var(--yp-radius-md);
  background: color-mix(in srgb, var(--yp-status-red) 5%, transparent);
}
.member-profile__access.is-disabled {
  border-color: var(--yp-border-subtle);
  background: color-mix(in srgb, var(--yp-bg-sunken) 60%, transparent);
}
.member-profile__access strong { color: var(--yp-text-primary); font-weight: 600; }
.member-profile__access p { margin: 2px 0 0; color: var(--yp-text-secondary); font-size: var(--yp-type-caption-size); line-height: var(--yp-type-caption-line); }
.member-profile__access .el-button { flex: none; }

@media (max-width: 640px) {
  .members-search { width: 100%; }
  .member-profile__access { align-items: stretch; flex-direction: column; }
}
</style>
