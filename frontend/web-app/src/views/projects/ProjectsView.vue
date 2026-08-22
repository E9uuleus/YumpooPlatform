<script setup lang="ts">
import { ArrowRight, Grid, Plus } from '@element-plus/icons-vue'
import {
  AccountStatus,
  EmploymentStatus,
  ProjectLifecycle,
  ProjectLifecycleFilter,
  ProjectType,
  ProductStatusFilter,
  readCsrfToken,
  type Member,
  type ProjectPage,
  type ProjectSummary,
  type ProjectTemplateVersion,
  type Product,
  type Workspace,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption as ElOptionRaw,
  ElPagination,
  ElSelect as ElSelectRaw,
  ElTable,
  ElTableColumn,
  type FormInstance,
  type FormRules,
} from 'element-plus'
import { computed, onMounted, reactive, ref, type DefineComponent } from 'vue'
import { useRouter } from 'vue-router'
import { identityAdministrationApi, productsApi, projectsApi, projectTemplatesApi, workspacesApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpFilterBar from '../../components/yp/YpFilterBar.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import { useSession } from '../../composables/useSession'
import { businessLabel } from '../../design-system/labels'
import { getStatusPresentation } from '../../design-system/status'
import type { ActiveFilter } from '../../design-system/types'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent

const router = useRouter()
const session = useSession()
const result = ref<ProjectPage>()
const workspaces = ref<Workspace[]>([])
const templates = ref<ProjectTemplateVersion[]>([])
const owners = ref<Member[]>([])
const workspaceId = ref<string>()
const projectType = ref<ProjectType>()
const lifecycle = ref<ProjectLifecycleFilter>()
const productId = ref<string>()
const products = ref<Product[]>([])
const productSearching = ref(false)
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const error = ref<ApiProblem>()
const createOpen = ref(false)
const creating = ref(false)
const collapsedWorkspaceIds = ref<ReadonlySet<string>>(new Set())
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  workspaceId: '',
  code: '',
  name: '',
  description: '',
  projectType: ProjectType.ProductDevelopment,
  ownerUserId: '',
  templateVersionId: '',
  customerName: '',
  customerReference: '',
  deliverySite: '',
  contactNote: '',
})
const emptyCreateForm = JSON.stringify(createForm)
const createRules: FormRules = {
  workspaceId: [{ required: true, message: '请选择 Workspace', trigger: 'change' }],
  templateVersionId: [{ required: true, message: '请选择模板版本', trigger: 'change' }],
  ownerUserId: [{ required: true, message: '请选择负责人', trigger: 'change' }],
  code: [{ required: true, whitespace: true, message: '请输入项目编码', trigger: 'blur' }],
  name: [{ required: true, whitespace: true, message: '请输入项目名称', trigger: 'blur' }],
}

const activeFilters = computed<ActiveFilter[]>(() => [
  ...(workspaceId.value
    ? [{
        key: 'workspace',
        label: 'Workspace',
        valueLabel: workspaces.value.find(item => item.id === workspaceId.value)?.name ?? workspaceId.value,
      }]
    : []),
  ...(projectType.value
    ? [{ key: 'type', label: '类型', valueLabel: businessLabel(projectType.value) }]
    : []),
  ...(lifecycle.value
    ? [{
        key: 'lifecycle',
        label: '状态',
        valueLabel: lifecycle.value === ProjectLifecycleFilter.All
          ? '全部'
          : getStatusPresentation('project-lifecycle', lifecycle.value).label,
      }]
    : []),
  ...(productId.value
    ? [{
        key: 'product',
        label: 'Product',
        valueLabel: products.value.find(item => item.id === productId.value)?.name ?? productId.value,
      }]
    : []),
])
const createDirty = computed(() => JSON.stringify(createForm) !== emptyCreateForm)

const projectGroupTones = ['blue', 'green', 'purple', 'teal', 'orange', 'pink'] as const
type ProjectGroupTone = typeof projectGroupTones[number]

interface ProjectBoardGroup {
  workspaceId: string
  workspaceCode: string
  workspaceName: string
  tone: ProjectGroupTone
  items: ProjectSummary[]
  activeCount: number
  draftCount: number
  archivedCount: number
}

function toneForWorkspace(workspaceId: string): ProjectGroupTone {
  let hash = 0
  for (const character of workspaceId) hash = ((hash << 5) - hash + character.charCodeAt(0)) | 0
  return projectGroupTones[Math.abs(hash) % projectGroupTones.length] ?? 'blue'
}

const projectGroups = computed<ProjectBoardGroup[]>(() => {
  const groups = new Map<string, ProjectBoardGroup>()
  for (const item of result.value?.items ?? []) {
    const group = groups.get(item.workspaceId) ?? {
      workspaceId: item.workspaceId,
      workspaceCode: item.workspaceCode,
      workspaceName: item.workspaceName,
      tone: toneForWorkspace(item.workspaceId),
      items: [],
      activeCount: 0,
      draftCount: 0,
      archivedCount: 0,
    }
    group.items.push(item)
    if (item.lifecycle === ProjectLifecycle.Active) group.activeCount += 1
    if (item.lifecycle === ProjectLifecycle.Draft) group.draftCount += 1
    if (item.lifecycle === ProjectLifecycle.Archived) group.archivedCount += 1
    groups.set(item.workspaceId, group)
  }
  const workspaceOrder = new Map(workspaces.value.map((workspace, index) => [workspace.id, index]))
  return [...groups.values()].sort((left, right) =>
    (workspaceOrder.get(left.workspaceId) ?? Number.MAX_SAFE_INTEGER)
      - (workspaceOrder.get(right.workspaceId) ?? Number.MAX_SAFE_INTEGER))
})

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    result.value = await projectsApi.listProjects({
      ...(workspaceId.value ? { workspaceId: workspaceId.value } : {}),
      ...(projectType.value ? { projectType: projectType.value } : {}),
      ...(lifecycle.value ? { lifecycle: lifecycle.value } : {}),
      ...(productId.value ? { productId: productId.value } : {}),
      page: page.value,
      size: size.value,
    })
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function searchProducts(query: string): Promise<void> {
  const normalized = query.trim()
  if (!normalized) {
    if (!productId.value) products.value = []
    return
  }
  productSearching.value = true
  try {
    products.value = (await productsApi.listProducts({
      status: ProductStatusFilter.Active,
      query: normalized,
      page: 0,
      size: 20,
    })).items
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    productSearching.value = false
  }
}

async function loadReferenceData(): Promise<void> {
  try {
    workspaces.value = (await workspacesApi.listWorkspaces({})).items
    if (session.isCompanyAdmin.value) {
      const [templatePage, memberPage] = await Promise.all([
        projectTemplatesApi.listProjectTemplates(),
        identityAdministrationApi.listMembers({
          employmentStatus: EmploymentStatus.Active,
          accountStatus: AccountStatus.Enabled,
          page: 0,
          size: 100,
        }),
      ])
      templates.value = templatePage.items
      owners.value = memberPage.items
    }
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

function applyFilters(): void {
  page.value = 0
  void load()
}

function removeFilter(key: string): void {
  if (key === 'workspace') workspaceId.value = undefined
  if (key === 'type') projectType.value = undefined
  if (key === 'lifecycle') lifecycle.value = undefined
  if (key === 'product') productId.value = undefined
  applyFilters()
}

function clearFilters(): void {
  workspaceId.value = undefined
  projectType.value = undefined
  lifecycle.value = undefined
  productId.value = undefined
  applyFilters()
}

function openProject(id: string): void {
  void router.push({ name: 'project-overview', params: { projectId: id } })
}

function openProjectRow(row: { id: string }): void {
  openProject(row.id)
}

function isGroupCollapsed(workspaceId: string): boolean {
  return collapsedWorkspaceIds.value.has(workspaceId)
}

function toggleGroup(workspaceId: string): void {
  const next = new Set(collapsedWorkspaceIds.value)
  if (next.has(workspaceId)) next.delete(workspaceId)
  else next.add(workspaceId)
  collapsedWorkspaceIds.value = next
}

function resetCreateForm(): void {
  Object.assign(createForm, JSON.parse(emptyCreateForm) as typeof createForm)
  createFormRef.value?.clearValidate()
}

async function beforeCreateClose(done: () => void): Promise<void> {
  if (!createDirty.value || creating.value) {
    done()
    return
  }
  try {
    await ElMessageBox.confirm('当前 Project 草稿尚未创建，关闭后输入内容将丢失。', '放弃创建？', {
      confirmButtonText: '放弃输入',
      cancelButtonText: '继续编辑',
      type: 'warning',
    })
    done()
  } catch {
    // Keep the drawer open.
  }
}

function requestCreateClose(): void {
  void beforeCreateClose(() => {
    createOpen.value = false
  })
}

async function createProject(): Promise<void> {
  try {
    await createFormRef.value?.validate()
  } catch {
    return
  }
  const csrf = readCsrfToken()
  const template = templates.value.find(item => item.templateVersionId === createForm.templateVersionId)
  if (!csrf || !template) {
    error.value = localProblem(csrf ? '模板版本不可用，请刷新后重试。' : '缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  creating.value = true
  error.value = undefined
  try {
    const project = await projectsApi.createProject({
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      projectCreateRequest: {
        workspaceId: createForm.workspaceId,
        code: createForm.code.trim().toUpperCase(),
        name: createForm.name.trim(),
        description: createForm.description.trim() || null,
        projectType: template.projectType,
        ownerUserId: createForm.ownerUserId,
        templateKey: template.templateKey,
        templateVersion: template.version,
        customerName: createForm.customerName.trim() || null,
        customerReference: createForm.customerReference.trim() || null,
        deliverySite: createForm.deliverySite.trim() || null,
        contactNote: createForm.contactNote.trim() || null,
      },
    })
    ElMessage.success('Project 草稿已创建')
    resetCreateForm()
    createOpen.value = false
    await Promise.all([load(), loadReferenceData()])
    openProject(project.id)
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    creating.value = false
  }
}

onMounted(async () => {
  await Promise.all([load(), loadReferenceData()])
})
</script>

<template>
  <section class="project-catalog">
    <project-workspace-header
      section="catalog"
      title="项目中心"
      description="按 Workspace 聚合项目状态、负责人和协作权限。"
    />

    <inline-problem
      v-if="error"
      :problem="error"
    />

    <div class="project-list-surface">
      <div class="project-board-view-switcher">
        <div
          class="project-board-view-switcher__active"
          aria-current="page"
        >
          <el-icon aria-hidden="true">
            <grid />
          </el-icon>
          <span>项目看板</span>
        </div>
        <span class="project-board-view-switcher__description">按 Workspace 分组</span>
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
        <template #filters>
          <el-select
            v-model="workspaceId"
            clearable
            placeholder="全部 Workspace"
            aria-label="Workspace"
          >
            <el-option
              v-for="item in workspaces"
              :key="item.id"
              :label="`${item.name} (${item.visibleProjectCount})`"
              :value="item.id"
            />
          </el-select>
          <el-select
            v-model="projectType"
            clearable
            placeholder="全部类型"
            aria-label="项目类型"
          >
            <el-option
              v-for="value in [ProjectType.ProductDevelopment, ProjectType.PreSales, ProjectType.Implementation, ProjectType.Hypercare]"
              :key="value"
              :label="businessLabel(value)"
              :value="value"
            />
          </el-select>
          <el-select
            v-model="lifecycle"
            clearable
            placeholder="草稿 + 活跃"
            aria-label="项目状态"
          >
            <el-option
              label="草稿"
              :value="ProjectLifecycleFilter.Draft"
            />
            <el-option
              label="活跃"
              :value="ProjectLifecycleFilter.Active"
            />
            <el-option
              label="已归档"
              :value="ProjectLifecycleFilter.Archived"
            />
            <el-option
              label="全部"
              :value="ProjectLifecycleFilter.All"
            />
          </el-select>
          <el-select
            v-model="productId"
            clearable
            filterable
            remote
            :remote-method="searchProducts"
            :loading="productSearching"
            placeholder="按 Product 筛选"
            aria-label="Product"
          >
            <el-option
              v-for="product in products"
              :key="product.id"
              :label="`${product.name} (${product.code})`"
              :value="product.id"
            />
          </el-select>
          <el-button
            type="primary"
            @click="applyFilters"
          >
            应用
          </el-button>
        </template>
        <template #actions>
          <el-button
            v-if="session.isCompanyAdmin.value"
            type="primary"
            @click="createOpen = true"
          >
            <el-icon aria-hidden="true">
              <plus />
            </el-icon>
            创建项目
          </el-button>
        </template>
      </yp-filter-bar>

      <div
        v-if="loading || projectGroups.length"
        v-loading="loading"
        class="project-board"
        :aria-busy="loading"
        aria-live="polite"
      >
        <section
          v-for="group in projectGroups"
          :key="group.workspaceId"
          class="project-board-group"
          :class="`project-board-group--${group.tone}`"
        >
          <header class="project-board-group__header">
            <button
              class="project-board-group__toggle"
              type="button"
              :aria-expanded="!isGroupCollapsed(group.workspaceId)"
              :aria-controls="`project-group-${group.workspaceId}`"
              @click="toggleGroup(group.workspaceId)"
            >
              <el-icon
                class="project-board-group__chevron"
                :class="{ collapsed: isGroupCollapsed(group.workspaceId) }"
                aria-hidden="true"
              >
                <arrow-right />
              </el-icon>
              <span class="project-board-group__accent" />
              <span class="project-board-group__identity">
                <strong>{{ group.workspaceName }}</strong>
                <small>{{ group.workspaceCode }} · {{ group.items.length }} 个项目</small>
              </span>
            </button>
            <div
              class="project-board-group__summary"
              :aria-label="`${group.workspaceName} 状态汇总`"
            >
              <span v-if="group.activeCount">活跃 {{ group.activeCount }}</span>
              <span v-if="group.draftCount">草稿 {{ group.draftCount }}</span>
              <span v-if="group.archivedCount">已归档 {{ group.archivedCount }}</span>
            </div>
          </header>

          <div
            v-if="!isGroupCollapsed(group.workspaceId)"
            :id="`project-group-${group.workspaceId}`"
            class="project-board-group__content"
          >
            <div class="table-scroll project-desktop-table">
              <el-table
                class="project-board-table"
                :data="group.items"
                @row-click="openProjectRow"
              >
                <el-table-column
                  fixed="left"
                  label="项目"
                  min-width="300"
                >
                  <template #default="scope">
                    <div class="project-name-cell">
                      <button
                        class="project-name-cell__link"
                        type="button"
                        @click.stop="openProject(scope.row.id)"
                      >
                        {{ scope.row.name }}
                      </button>
                      <span class="project-name-cell__code">{{ scope.row.code }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  class-name="project-status-column"
                  label="状态"
                  width="132"
                >
                  <template #default="scope">
                    <yp-status-tag
                      domain="project-lifecycle"
                      :status="scope.row.lifecycle"
                      effect="cell"
                    />
                  </template>
                </el-table-column>
                <el-table-column
                  label="负责人"
                  min-width="180"
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
                  label="项目类型"
                  min-width="150"
                >
                  <template #default="scope">
                    {{ businessLabel(scope.row.projectType) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="我的角色"
                  min-width="120"
                >
                  <template #default="scope">
                    {{ businessLabel(scope.row.actorAccess) }}
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <ul
              class="project-mobile-list"
              :aria-label="`${group.workspaceName} 项目列表`"
            >
              <li
                v-for="item in group.items"
                :key="item.id"
              >
                <button
                  class="project-mobile-row"
                  type="button"
                  :aria-label="`打开项目 ${item.name}`"
                  @click="openProject(item.id)"
                >
                  <span class="project-mobile-row__header">
                    <span class="project-mobile-row__identity">
                      <strong>{{ item.name }}</strong>
                      <span>{{ item.code }}</span>
                    </span>
                    <yp-status-tag
                      domain="project-lifecycle"
                      :status="item.lifecycle"
                      size="small"
                    />
                  </span>
                  <span class="project-mobile-row__meta">
                    <yp-assignee
                      :user-id="item.ownerUserId"
                      :display-name="item.ownerDisplayName"
                      size="table"
                    />
                    <span>{{ businessLabel(item.projectType) }} · {{ businessLabel(item.actorAccess) }}</span>
                  </span>
                </button>
              </li>
            </ul>
          </div>
        </section>
      </div>
      <yp-empty-state
        v-else
        reason="no-results"
        description="没有符合当前筛选条件的 Project。"
        compact
      >
        <template
          v-if="activeFilters.length"
          #action
        >
          <el-button @click="clearFilters">
            清除筛选
          </el-button>
        </template>
      </yp-empty-state>
    </div>

    <el-pagination
      v-if="result && result.totalElements > 0"
      class="page-control"
      layout="prev, pager, next, total"
      :current-page="page + 1"
      :page-size="size"
      :total="result.totalElements"
      @current-change="next => { page = next - 1; load() }"
    />

    <el-drawer
      v-model="createOpen"
      class="project-create-drawer"
      title="创建 Project 草稿"
      size="min(620px, 100vw)"
      :before-close="beforeCreateClose"
      @closed="resetCreateForm"
    >
      <el-form
        ref="createFormRef"
        label-position="top"
        :model="createForm"
        :rules="createRules"
      >
        <el-form-item
          label="Workspace"
          prop="workspaceId"
        >
          <el-select
            v-model="createForm.workspaceId"
            placeholder="请选择 Workspace"
          >
            <el-option
              v-for="item in workspaces"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          label="固化模板版本"
          prop="templateVersionId"
        >
          <el-select
            v-model="createForm.templateVersionId"
            placeholder="请选择模板"
          >
            <el-option
              v-for="item in templates"
              :key="item.templateVersionId"
              :label="`${item.displayName} / ${item.versionCode}`"
              :value="item.templateVersionId"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          label="负责人"
          prop="ownerUserId"
        >
          <el-select
            v-model="createForm.ownerUserId"
            filterable
            placeholder="请选择负责人"
          >
            <el-option
              v-for="item in owners"
              :key="item.userId"
              :label="item.displayName"
              :value="item.userId"
            />
          </el-select>
        </el-form-item>
        <div class="form-grid">
          <el-form-item
            label="项目编码"
            prop="code"
          >
            <el-input
              v-model="createForm.code"
              maxlength="32"
            />
          </el-form-item>
          <el-form-item
            label="项目名称"
            prop="name"
          >
            <el-input
              v-model="createForm.name"
              maxlength="80"
            />
          </el-form-item>
        </div>
        <el-form-item label="描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="客户名称">
          <el-input
            v-model="createForm.customerName"
            maxlength="160"
          />
          <small class="muted-text">草稿阶段可空；非研发项目激活前必须补齐。</small>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="客户参考号">
            <el-input
              v-model="createForm.customerReference"
              maxlength="80"
            />
          </el-form-item>
          <el-form-item label="交付地点">
            <el-input
              v-model="createForm.deliverySite"
              maxlength="160"
            />
          </el-form-item>
        </div>
        <el-form-item label="联系备注">
          <el-input
            v-model="createForm.contactNote"
            type="textarea"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <div class="action-row">
          <el-button @click="requestCreateClose">
            取消
          </el-button>
          <el-button
            type="primary"
            :loading="creating"
            @click="createProject"
          >
            创建草稿
          </el-button>
        </div>
      </el-form>
    </el-drawer>
  </section>
</template>
