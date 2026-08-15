<script setup lang="ts">
import {
  AccountStatus,
  EmploymentStatus,
  readCsrfToken,
  type Member,
  type MemberPage,
} from '@yumpoo/api-client'
import {
  ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElDrawer, ElEmpty,
  ElMessage, ElMessageBox, ElPagination,
  ElTable, ElTableColumn, ElTag,
} from 'element-plus'
import { onMounted, ref } from 'vue'
import { identityAdministrationApi, identityGovernanceApi } from '../../api/client'
import { toUiError, useIdentityAdmin, type UiError } from '../../composables/useIdentityAdmin'

const { canWrite } = useIdentityAdmin()
const result = ref<MemberPage>()
const loading = ref(false)
const error = ref<UiError>()
const name = ref('')
const externalUserId = ref('')
const employmentStatus = ref<EmploymentStatus>()
const accountStatus = ref<AccountStatus>()
const page = ref(0)
const size = ref(20)
const selected = ref<Member>()
const drawerOpen = ref(false)
const changingUserId = ref<string>()

function formatTime(value?: Date | null): string {
  return value ? value.toLocaleString('zh-CN') : '—'
}

function accountType(value: AccountStatus): 'success' | 'danger' {
  return value === AccountStatus.Enabled ? 'success' : 'danger'
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
      page: page.value,
      size: size.value,
    })
  } catch (reason) {
    error.value = await toUiError(reason)
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
    error.value = await toUiError(reason)
  }
}

function applyFilters(): void {
  page.value = 0
  void load()
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
      `${action}成员「${member.displayName}」。就业状态保持 ${member.employmentStatus} 不变；${sessionImpact}`,
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
    error.value = { message: '缺少 CSRF 凭据，请刷新页面后重试。' }
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
    const uiError = await toUiError(failure)
    error.value = uiError
    if (uiError.status === 412) {
      await load()
      if (drawerOpen.value) await openMember(member.userId)
    }
  } finally {
    changingUserId.value = undefined
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
    <div class="member-filters">
      <input v-model="name" class="native-control" placeholder="姓名包含" @keyup.enter="applyFilters">
      <input v-model="externalUserId" class="native-control" placeholder="企微外部 ID（精确）" @keyup.enter="applyFilters">
      <select v-model="employmentStatus" class="native-control" aria-label="就业状态">
        <option :value="undefined">全部就业状态</option>
        <option v-for="value in EmploymentStatus" :key="value" :value="value">{{ value }}</option>
      </select>
      <select v-model="accountStatus" class="native-control" aria-label="账号状态">
        <option :value="undefined">全部账号状态</option>
        <option v-for="value in AccountStatus" :key="value" :value="value">{{ value }}</option>
      </select>
      <el-button type="primary" @click="applyFilters">查询</el-button>
    </div>

    <el-alert
      v-if="error"
      class="inline-error"
      type="error"
      :closable="false"
      :title="error.message"
      :description="error.requestId ? `requestId: ${error.requestId}` : ''"
      show-icon
    />

    <el-table v-if="result?.items.length" v-loading="loading" :data="result.items" stripe>
      <el-table-column prop="displayName" label="姓名" min-width="150" />
      <el-table-column prop="externalUserId" label="企微外部 ID" min-width="180" />
      <el-table-column prop="departmentSummary" label="部门" min-width="180" />
      <el-table-column prop="employmentStatus" label="就业" width="100" />
      <el-table-column prop="accountStatus" label="账号" width="110">
        <template #default="scope"><el-tag :type="accountType(scope.row.accountStatus)">{{ scope.row.accountStatus }}</el-tag></template>
      </el-table-column>
      <el-table-column label="角色" min-width="180">
        <template #default="scope">
          <el-tag v-for="role in scope.row.platformRoles" :key="role" class="role-tag" effect="plain">{{ role }}</el-tag>
          <span v-if="scope.row.platformRoles.length === 0">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="openMember(scope.row.userId)">详情</el-button>
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
    <el-empty v-else-if="!loading" description="没有符合条件的成员" />

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

    <el-drawer v-model="drawerOpen" title="成员详情" size="560px">
      <el-descriptions v-if="selected" :column="1" border>
        <el-descriptions-item label="姓名">{{ selected.displayName }}</el-descriptions-item>
        <el-descriptions-item label="企微外部 ID">{{ selected.externalUserId }}</el-descriptions-item>
        <el-descriptions-item label="部门">{{ selected.departmentSummary ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ selected.email ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="手机">{{ selected.mobile ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="就业状态">{{ selected.employmentStatus }}</el-descriptions-item>
        <el-descriptions-item label="账号状态"><el-tag :type="accountType(selected.accountStatus)">{{ selected.accountStatus }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="最近同步">{{ formatTime(selected.directorySyncedAt) }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ Array.from(selected.platformRoles).join('、') || '无平台管理角色' }}</el-descriptions-item>
      </el-descriptions>
      <el-button
        v-if="selected && canWrite"
        class="drawer-action"
        :type="selected.accountStatus === AccountStatus.Enabled ? 'danger' : 'success'"
        :loading="changingUserId === selected.userId"
        @click="changeAccount(selected)"
      >
        {{ selected.accountStatus === AccountStatus.Enabled ? '停用账号' : '启用账号' }}
      </el-button>
    </el-drawer>
  </section>
</template>
