<script setup lang="ts">
import {
  AccountStatus, EmploymentStatus, ProjectLifecycleFilter, ProjectType, readCsrfToken,
  type Member, type ProjectPage, type ProjectTemplateVersion, type Workspace,
} from '@yumpoo/api-client'
import {
  ElButton, ElDrawer, ElEmpty, ElForm, ElFormItem, ElInput, ElMessage,
  ElPagination, ElTable, ElTableColumn, ElTag,
} from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { identityAdministrationApi, projectsApi, projectTemplatesApi, workspacesApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import { useSession } from '../../composables/useSession'

const router = useRouter()
const session = useSession()
const result = ref<ProjectPage>()
const workspaces = ref<Workspace[]>([])
const templates = ref<ProjectTemplateVersion[]>([])
const owners = ref<Member[]>([])
const workspaceId = ref<string>()
const projectType = ref<ProjectType>()
const lifecycle = ref<ProjectLifecycleFilter>()
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const error = ref<ApiProblem>()
const createOpen = ref(false)
const creating = ref(false)
const createForm = reactive({
  workspaceId: '', code: '', name: '', description: '', projectType: ProjectType.ProductDevelopment,
  ownerUserId: '', templateVersionId: '', customerName: '', customerReference: '',
  deliverySite: '', contactNote: '',
})

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    result.value = await projectsApi.listProjects({
      ...(workspaceId.value ? { workspaceId: workspaceId.value } : {}),
      ...(projectType.value ? { projectType: projectType.value } : {}),
      ...(lifecycle.value ? { lifecycle: lifecycle.value } : {}),
      page: page.value, size: size.value,
    })
  } catch (reason) { error.value = await toApiProblem(reason) } finally { loading.value = false }
}

async function loadReferenceData(): Promise<void> {
  try {
    workspaces.value = (await workspacesApi.listWorkspaces({})).items
    if (session.isCompanyAdmin.value) {
      const [templatePage, memberPage] = await Promise.all([
        projectTemplatesApi.listProjectTemplates(),
        identityAdministrationApi.listMembers({ employmentStatus: EmploymentStatus.Active,
          accountStatus: AccountStatus.Enabled, page: 0, size: 100 }),
      ])
      templates.value = templatePage.items
      owners.value = memberPage.items
    }
  } catch (reason) { error.value = await toApiProblem(reason) }
}

function applyFilters(): void { page.value = 0; void load() }
function openProject(id: string): void { void router.push({ name: 'project-overview', params: { projectId: id } }) }
function openProjectRow(row: { id: string }): void { openProject(row.id) }

async function createProject(): Promise<void> {
  const csrf = readCsrfToken()
  const template = templates.value.find(item => item.templateVersionId === createForm.templateVersionId)
  if (!csrf || !template || !createForm.workspaceId || !createForm.ownerUserId
      || !createForm.code.trim() || !createForm.name.trim()) {
    error.value = localProblem(csrf ? '请完整填写 Workspace、模板、负责人、编码和名称。' : '缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  creating.value = true
  error.value = undefined
  try {
    const project = await projectsApi.createProject({
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      projectCreateRequest: {
        workspaceId: createForm.workspaceId, code: createForm.code.trim().toUpperCase(),
        name: createForm.name.trim(), description: createForm.description.trim() || null,
        projectType: template.projectType, ownerUserId: createForm.ownerUserId,
        templateKey: template.templateKey, templateVersion: template.version,
        customerName: createForm.customerName.trim() || null,
        customerReference: createForm.customerReference.trim() || null,
        deliverySite: createForm.deliverySite.trim() || null,
        contactNote: createForm.contactNote.trim() || null,
      },
    })
    ElMessage.success('Project 草稿已创建')
    createOpen.value = false
    await Promise.all([load(), loadReferenceData()])
    openProject(project.id)
  } catch (reason) { error.value = await toApiProblem(reason) } finally { creating.value = false }
}

onMounted(async () => { await Promise.all([load(), loadReferenceData()]) })
</script>

<template>
  <section class="project-page">
    <div class="page-title">
      <div><p class="eyebrow">PROJECT CATALOG</p><h2>项目工作台</h2><p>仅展示当前账号可见的 Project。</p></div>
      <el-button v-if="session.isCompanyAdmin.value" type="primary" @click="createOpen = true">创建 Project</el-button>
    </div>
    <inline-problem v-if="error" class="inline-error" :problem="error" />
    <div class="project-filters">
      <select v-model="workspaceId" class="native-control"><option :value="undefined">全部 Workspace</option><option v-for="item in workspaces" :key="item.id" :value="item.id">{{ item.name }} ({{ item.visibleProjectCount }})</option></select>
      <select v-model="projectType" class="native-control"><option :value="undefined">全部类型</option><option :value="ProjectType.ProductDevelopment">产品研发</option><option :value="ProjectType.PreSales">售前</option><option :value="ProjectType.Implementation">实施</option><option :value="ProjectType.Hypercare">运维保障</option></select>
      <select v-model="lifecycle" class="native-control"><option :value="undefined">草稿 + 活跃</option><option :value="ProjectLifecycleFilter.Draft">草稿</option><option :value="ProjectLifecycleFilter.Active">活跃</option><option :value="ProjectLifecycleFilter.Archived">已归档</option><option :value="ProjectLifecycleFilter.All">全部</option></select>
      <el-button @click="applyFilters">查询</el-button>
    </div>
    <el-table v-loading="loading" :data="result?.items ?? []" border @row-click="openProjectRow">
      <el-table-column prop="code" label="编码" width="150" />
      <el-table-column prop="name" label="项目名称" min-width="220" />
      <el-table-column prop="workspaceName" label="Workspace" min-width="160" />
      <el-table-column prop="projectType" label="类型" width="170" />
      <el-table-column label="状态" width="110"><template #default="scope"><el-tag effect="plain">{{ scope.row.lifecycle }}</el-tag></template></el-table-column>
      <el-table-column prop="ownerDisplayName" label="负责人" min-width="140" />
    </el-table>
    <el-empty v-if="!loading && !result?.items.length" description="没有符合条件的 Project" />
    <el-pagination class="page-control" layout="prev, pager, next, total" :current-page="page + 1"
      :page-size="size" :total="result?.totalElements ?? 0" @current-change="next => { page = next - 1; load() }" />

    <el-drawer v-model="createOpen" title="创建 Project 草稿" size="520px">
      <el-form label-position="top" :model="createForm">
        <el-form-item label="Workspace"><select v-model="createForm.workspaceId" class="native-control"><option value="">请选择</option><option v-for="item in workspaces" :key="item.id" :value="item.id">{{ item.name }}</option></select></el-form-item>
        <el-form-item label="固化模板版本"><select v-model="createForm.templateVersionId" class="native-control"><option value="">请选择</option><option v-for="item in templates" :key="item.templateVersionId" :value="item.templateVersionId">{{ item.displayName }} / {{ item.versionCode }}</option></select></el-form-item>
        <el-form-item label="负责人"><select v-model="createForm.ownerUserId" class="native-control"><option value="">请选择</option><option v-for="item in owners" :key="item.userId" :value="item.userId">{{ item.displayName }}</option></select></el-form-item>
        <div class="form-grid"><el-form-item label="项目编码"><el-input v-model="createForm.code" maxlength="32" /></el-form-item><el-form-item label="项目名称"><el-input v-model="createForm.name" maxlength="80" /></el-form-item></div>
        <el-form-item label="描述"><el-input v-model="createForm.description" type="textarea" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item label="客户名称"><el-input v-model="createForm.customerName" maxlength="160" /><small>草稿阶段可空；非研发项目激活前必须补齐。</small></el-form-item>
        <div class="form-grid"><el-form-item label="客户参考号"><el-input v-model="createForm.customerReference" maxlength="80" /></el-form-item><el-form-item label="交付地点"><el-input v-model="createForm.deliverySite" maxlength="160" /></el-form-item></div>
        <el-form-item label="联系备注"><el-input v-model="createForm.contactNote" type="textarea" maxlength="500" show-word-limit /></el-form-item>
        <el-button type="primary" :loading="creating" @click="createProject">创建草稿</el-button>
      </el-form>
    </el-drawer>
  </section>
</template>
