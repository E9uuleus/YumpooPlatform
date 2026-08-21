<script setup lang="ts">
import {
  ProjectActorAccess, ProjectMembershipStatusFilter, readCsrfToken,
  type ProjectDetail, type ProjectMember, type ProjectMemberCandidate, type ProjectMemberPage,
} from '@yumpoo/api-client'
import { ElButton, ElEmpty, ElInput, ElMessage, ElMessageBox, ElPagination, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'

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

async function load(): Promise<void> {
  loading.value = true
  try {
    const [detail, members] = await Promise.all([
      projectsApi.getProject({ projectId }),
      projectsApi.listProjectMembers({ projectId, status: status.value, page: page.value, size: size.value }),
    ])
    project.value = detail; result.value = members
  } catch (reason) { error.value = await toApiProblem(reason) } finally { loading.value = false }
}
async function searchCandidates(): Promise<void> {
  if (!name.value.trim()) { candidates.value = []; return }
  try { candidates.value = (await projectsApi.listProjectMemberCandidates({ projectId, name: name.value.trim(), page: 0, size: 20 })).items }
  catch (reason) { error.value = await toApiProblem(reason) }
}
async function reasonFor(action: string, required = governanceReasonRequired.value): Promise<string | null | undefined> {
  try {
    const response = await ElMessageBox.prompt(`${action}${required ? '；管理员治理路径必须记录理由。' : '；可选填理由。'}`, action,
      { inputPlaceholder: required ? '请输入 10～500 字治理理由' : '可选理由',
        inputPattern: required ? /^(?=[\s\S]{10,500}$)(?=\s*\S)/ : /^(?:|(?=\s*\S)[\s\S]{1,500})$/,
        inputErrorMessage: required ? '治理理由必须为 10～500 字' : '理由不得超过 500 字' })
    return response.value.trim() || null
  } catch { return undefined }
}
async function add(candidate: ProjectMemberCandidate): Promise<void> {
  const reason = await reasonFor(candidate.membershipStatus === 'REMOVED' ? '重激活成员' : '加入成员')
  if (reason === undefined) return
  const csrf = readCsrfToken(); if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  changing.value = candidate.userId
  try {
    await projectsApi.addProjectMember({ projectId, xXSRFTOKEN: csrf, idempotencyKey: crypto.randomUUID(),
      projectMemberAddRequest: { userId: candidate.userId, reason },
      ...(candidate.membershipEtag ? { ifMatch: candidate.membershipEtag } : {}) })
    ElMessage.success(candidate.membershipStatus === 'REMOVED' ? '成员已重激活' : '成员已加入')
    candidates.value = []; name.value = ''; await load()
  } catch (reasonValue) { error.value = await toApiProblem(reasonValue); await refreshOnConflict(error.value) }
  finally { changing.value = undefined }
}
async function remove(member: ProjectMember): Promise<void> {
  const reason = await reasonFor(`移除成员「${member.displayName}」`); if (reason === undefined) return
  const csrf = readCsrfToken(); if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  changing.value = member.userId
  try {
    await projectsApi.removeProjectMember({ projectId, userId: member.userId, xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(), ifMatch: member.etag, projectMemberRemoveRequest: { reason } })
    ElMessage.success('成员已移除'); await load()
  } catch (reasonValue) { error.value = await toApiProblem(reasonValue); await refreshOnConflict(error.value) }
  finally { changing.value = undefined }
}
async function reassign(candidate: ProjectMemberCandidate): Promise<void> {
  const reason = await reasonFor(`将负责人重指派为「${candidate.displayName}」`, true); if (!reason) return
  const csrf = readCsrfToken(); if (!csrf || !project.value) { error.value = localProblem('缺少并发或 CSRF 凭据，请刷新后重试。'); return }
  changing.value = candidate.userId
  try {
    await projectsApi.reassignProjectOwner({ projectId, xXSRFTOKEN: csrf, idempotencyKey: crypto.randomUUID(),
      ifMatch: project.value.etag, projectOwnerReassignmentRequest: { newOwnerUserId: candidate.userId, reason } })
    ElMessage.success('负责人已重指派'); candidates.value = []; name.value = ''; await load()
  } catch (reasonValue) { error.value = await toApiProblem(reasonValue); await refreshOnConflict(error.value) }
  finally { changing.value = undefined }
}
async function refreshOnConflict(problem?: ApiProblem): Promise<void> { if (problem && isProblemStatus(problem, 412)) await load() }
function removeRow(row: unknown): void { void remove(row as ProjectMember) }
onMounted(load)
</script>

<template>
  <div>
    <div class="page-title compact"><div><p class="eyebrow">PROJECT MEMBERS</p><h2>成员治理</h2><p>Owner 执行日常管理，Company Admin 执行治理与负责人重指派。</p></div></div>
    <inline-problem v-if="error" class="inline-error" :problem="error" />
    <div class="project-filters">
      <select v-model="status" class="native-control" @change="page = 0; load()"><option :value="ProjectMembershipStatusFilter.All">全部</option><option :value="ProjectMembershipStatusFilter.Active">活跃</option><option :value="ProjectMembershipStatusFilter.Removed">已移除</option></select>
      <el-input v-model="name" placeholder="搜索可加入的成员" clearable @keyup.enter="searchCandidates" />
      <el-button :disabled="!project?.capabilities.canManageMembers" @click="searchCandidates">搜索候选人</el-button>
    </div>
    <div v-if="candidates.length" class="candidate-panel">
      <strong>候选人</strong>
      <div v-for="candidate in candidates" :key="candidate.userId" class="candidate-row">
        <span>{{ candidate.displayName }} <el-tag v-if="candidate.membershipStatus" size="small" effect="plain">{{ candidate.membershipStatus }}</el-tag></span>
        <span><el-button size="small" :loading="changing === candidate.userId" :disabled="candidate.owner || candidate.membershipStatus === 'ACTIVE'" @click="add(candidate)">加入 / 重激活</el-button><el-button v-if="project?.capabilities.canReassignOwner" size="small" @click="reassign(candidate)">设为负责人</el-button></span>
      </div>
    </div>
    <el-table v-loading="loading" :data="result?.items ?? []" border>
      <el-table-column prop="displayName" label="成员" min-width="180" />
      <el-table-column label="角色" width="110"><template #default="scope"><el-tag v-if="scope.row.owner" type="warning">Owner</el-tag><span v-else>成员</span></template></el-table-column>
      <el-table-column prop="membershipStatus" label="状态" width="120" />
      <el-table-column label="操作" width="130"><template #default="scope"><el-button v-if="scope.row.membershipStatus === 'ACTIVE' && !scope.row.owner" link type="danger" :loading="changing === scope.row.userId" :disabled="!project?.capabilities.canManageMembers" @click="removeRow(scope.row)">移除</el-button></template></el-table-column>
    </el-table>
    <el-empty v-if="!loading && !result?.items.length" description="暂无成员" />
    <el-pagination class="page-control" layout="prev, pager, next, total" :current-page="page + 1" :page-size="size" :total="result?.totalElements ?? 0" @current-change="next => { page = next - 1; load() }" />
  </div>
</template>
