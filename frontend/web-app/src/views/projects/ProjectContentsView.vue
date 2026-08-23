<script setup lang="ts">
import { ArrowDown, ArrowUp, Edit, Plus } from '@element-plus/icons-vue'
import {
  ContentStatus,
  ContentTableColumn,
  ContentViewType,
  ProjectLifecycle,
  ProjectMembershipStatusFilter,
  readCsrfToken,
  type Content,
  type ContentBlueprintOption,
  type ContentViewConfig,
  type ProjectContentCatalog,
  type ProjectDetail,
  type ProjectMember,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElCheckbox,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption as ElOptionRaw,
  ElSelect as ElSelectRaw,
  ElTable,
  ElTableColumn,
} from 'element-plus'
import { computed, onMounted, ref, type DefineComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { contentsApi, projectsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpFilterBar from '../../components/yp/YpFilterBar.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import ContentTableQueryEditor from '../../components/projects/ContentTableQueryEditor.vue'
import type { ContentTableQuery } from '../../components/projects/contentTableQuery'

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const route = useRoute()
const router = useRouter()
const projectId = String(route.params.projectId)
const project = ref<ProjectDetail>()
const catalog = ref<ProjectContentCatalog>()
const loading = ref(false)
const changing = ref<string>()
const error = ref<ApiProblem>()
const query = ref('')
const statusFilter = ref<string>('ALL')
const createOpen = ref(false)
const creating = ref(false)
const createForm = ref({ code: '', name: '', description: '', blueprintCode: '' })
const drawerOpen = ref(false)
const saving = ref(false)
const selected = ref<Content>()
const latestConflict = ref<Content>()
const draftName = ref('')
const draftDescription = ref('')
const draftViewType = ref(ContentViewType.Table)
const draftConfig = ref<ContentViewConfig>()
const members = ref<ProjectMember[]>([])

const items = computed(() => catalog.value?.items ?? [])
const canWrite = computed(() => catalog.value?.canCreate === true)
const readOnlyReason = computed(() => {
  if (project.value?.lifecycle === ProjectLifecycle.Archived) return 'Project 已归档，Content 仅可查看。'
  if (!canWrite.value) return '当前角色拥有 Content 只读权限；仅 Project Owner 可以修改。'
  return undefined
})
const visibleItems = computed(() => items.value.filter(item => {
  const matchesStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value
  const keyword = query.value.trim().toLocaleLowerCase()
  return matchesStatus && (!keyword || `${item.code} ${item.name} ${item.description ?? ''}`
    .toLocaleLowerCase().includes(keyword))
}))
const activeFilters = computed(() => statusFilter.value === 'ALL' ? [] : [{
  key: 'status', label: '状态', valueLabel: statusFilter.value === 'ACTIVE' ? '使用中' : '已归档',
}])
const orderedColumns = computed(() => draftConfig.value
  ? Array.from(draftConfig.value.table.columnOrder) : [])
const hiddenColumns = computed(() => draftConfig.value?.table.hiddenColumns ?? new Set())
const workflowStatuses = computed(() => catalog.value?.workflowStatusOptions ?? [])

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    const [detail, nextCatalog] = await Promise.all([
      projectsApi.getProject({ projectId }),
      contentsApi.listProjectContents({ projectId }),
    ])
    project.value = detail
    catalog.value = nextCatalog
    await loadMembers()
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function loadMembers(): Promise<void> {
  const loaded: ProjectMember[] = []
  let page = 0
  let totalPages = 1
  while (page < totalPages) {
    const result = await projectsApi.listProjectMembers({
      projectId, status: ProjectMembershipStatusFilter.All, page, size: 100,
    })
    loaded.push(...result.items)
    totalPages = result.totalPages
    page += 1
  }
  members.value = loaded
}

function openCreate(): void {
  const first = catalog.value?.blueprintOptions[0]
  createForm.value = { code: '', name: '', description: '', blueprintCode: first?.blueprintCode ?? '' }
  createOpen.value = true
}

function selectBlueprint(option?: ContentBlueprintOption): void {
  if (!option || createForm.value.name) return
  createForm.value.name = option.displayName
}

async function createContent(): Promise<void> {
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  creating.value = true
  try {
    await contentsApi.createContent({ projectId, xXSRFTOKEN: csrf, idempotencyKey: crypto.randomUUID(),
      contentCreateRequest: {
        code: createForm.value.code.trim().toUpperCase(), name: createForm.value.name.trim(),
        description: createForm.value.description.trim() || null,
        blueprintCode: createForm.value.blueprintCode,
      } })
    ElMessage.success('Content 已创建')
    createOpen.value = false
    await load()
  } catch (reason) { error.value = await toApiProblem(reason) }
  finally { creating.value = false }
}

function openDrawer(content: Content): void {
  selected.value = content
  latestConflict.value = undefined
  draftName.value = content.name
  draftDescription.value = content.description ?? ''
  draftViewType.value = content.defaultViewType
  draftConfig.value = cloneConfig(content.viewConfig)
  drawerOpen.value = true
}

function openWorkspace(content: Content): void {
  void router.push({
    name: 'content-work-items',
    params: { projectId, contentId: content.id },
  })
}

function cloneConfig(value: ContentViewConfig): ContentViewConfig {
  return {
    table: {
      columnOrder: new Set(value.table.columnOrder), hiddenColumns: new Set(value.table.hiddenColumns),
      sort: value.table.sort.map(item => ({ ...item })),
      filters: { ...value.table.filters, statusCodes: new Set(value.table.filters.statusCodes),
        priorities: new Set(value.table.filters.priorities),
        assigneeUserIds: new Set(value.table.filters.assigneeUserIds) },
    },
    kanban: { statusGroups: value.kanban.statusGroups.map(group => ({ ...group, statusCodes: new Set(group.statusCodes) })) },
  }
}

function updateDraftQuery(value: ContentTableQuery): void {
  if (!draftConfig.value) return
  draftConfig.value.table.filters = value.filters
  draftConfig.value.table.sort = value.sort
}

async function save(etag = selected.value?.etag): Promise<void> {
  if (!selected.value || !draftConfig.value || !etag) return
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  saving.value = true
  try {
    const updated = await contentsApi.updateContent({ contentId: selected.value.id, xXSRFTOKEN: csrf,
      ifMatch: etag, contentUpdateRequest: { name: draftName.value.trim(),
        description: draftDescription.value.trim() || null, defaultViewType: draftViewType.value,
        viewConfig: draftConfig.value } })
    ElMessage.success('Content 配置已保存')
    selected.value = updated
    latestConflict.value = undefined
    await load()
  } catch (reason) {
    const problem = await toApiProblem(reason)
    error.value = problem
    if (isProblemStatus(problem, 412)) {
      try { latestConflict.value = await contentsApi.getContent({ contentId: selected.value.id }) }
      catch (latestReason) { error.value = await toApiProblem(latestReason) }
    }
  } finally { saving.value = false }
}

function loadLatest(): void {
  if (!latestConflict.value) return
  openDrawer(latestConflict.value)
}

async function retryQueryMerge(): Promise<void> {
  if (!latestConflict.value || !draftConfig.value) return
  const filters = draftConfig.value.table.filters
  const sort = draftConfig.value.table.sort
  const latest = latestConflict.value
  selected.value = latest
  draftName.value = latest.name
  draftDescription.value = latest.description ?? ''
  draftViewType.value = latest.defaultViewType
  draftConfig.value = cloneConfig(latest.viewConfig)
  draftConfig.value.table.filters = filters
  draftConfig.value.table.sort = sort
  latestConflict.value = undefined
  await save(latest.etag)
}

async function transition(content: Content): Promise<void> {
  const archive = content.status === ContentStatus.Active
  try {
    await ElMessageBox.confirm(`确认${archive ? '归档' : '恢复'}「${content.name}」？`,
      `${archive ? '归档' : '恢复'} Content`, { type: archive ? 'warning' : 'info',
        confirmButtonText: archive ? '归档' : '恢复', cancelButtonText: '取消' })
  } catch { return }
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  changing.value = content.id
  try {
    const request = { contentId: content.id, xXSRFTOKEN: csrf, ifMatch: content.etag,
      idempotencyKey: crypto.randomUUID() }
    if (archive) await contentsApi.archiveContent(request)
    else await contentsApi.restoreContent(request)
    ElMessage.success(`Content 已${archive ? '归档' : '恢复'}`)
    drawerOpen.value = false
    await load()
  } catch (reason) { error.value = await toApiProblem(reason); if (error.value && isProblemStatus(error.value, 412)) await load() }
  finally { changing.value = undefined }
}

function moveColumn(index: number, offset: number): void {
  if (!draftConfig.value) return
  const columns = Array.from(draftConfig.value.table.columnOrder)
  const target = index + offset
  if (target < 0 || target >= columns.length) return
  const current = columns[index]
  const replacement = columns[target]
  if (!current || !replacement) return
  columns[index] = replacement
  columns[target] = current
  draftConfig.value.table.columnOrder = new Set(columns)
}

function toggleColumn(column: ContentTableColumn, visible: boolean): void {
  if (!draftConfig.value || column === ContentTableColumn.Title) return
  const hidden = new Set(draftConfig.value.table.hiddenColumns)
  if (visible) hidden.delete(column); else hidden.add(column)
  draftConfig.value.table.hiddenColumns = hidden
}

function resetKanban(): void {
  if (!draftConfig.value) return
  draftConfig.value.kanban.statusGroups = workflowStatuses.value.map(status => ({
    name: status.displayName, statusCodes: new Set([status.statusCode]),
  }))
}

function addKanbanGroup(): void {
  draftConfig.value?.kanban.statusGroups.push({ name: '新分组', statusCodes: new Set() })
}

function removeKanbanGroup(index: number): void {
  draftConfig.value?.kanban.statusGroups.splice(index, 1)
}

function columnLabel(column: ContentTableColumn): string {
  return ({ ITEM_NO: '编号', TITLE: '标题', STATUS: '状态', PRIORITY: '优先级', ASSIGNEE: '负责人',
    REPORTER: '报告人', DESCRIPTION: '描述', NOTES: '备注', TIMELINE: '时间线',
    DUE_DATE: '截止日期', UPDATED_AT: '更新时间' } as Record<string, string>)[column] ?? column
}

function workItemLabel(value: string): string {
  return ({ REQUIREMENT: '需求', TASK: '任务', DEFECT: '缺陷' } as Record<string, string>)[value] ?? value
}

function contentRow(value: unknown): Content { return value as Content }

function clearFilter(): void { statusFilter.value = 'ALL' }
onMounted(load)
</script>

<template>
  <div class="project-view-stack content-workspace" v-loading="loading">
    <project-workspace-header section="contents" :project="project" title="Content"
      description="按固定模板管理工作项容器与默认视图。">
      <template #primary-action>
        <el-button v-if="canWrite" type="primary" @click="openCreate">
          <el-icon aria-hidden="true"><plus /></el-icon>新建 Content
        </el-button>
      </template>
    </project-workspace-header>
    <inline-problem v-if="error" :problem="error" />
    <div v-if="readOnlyReason" class="content-readonly-banner" role="status">{{ readOnlyReason }}</div>

    <section class="content-board" aria-label="Content 目录">
      <yp-filter-bar :filters="activeFilters" :result-count="visibleItems.length" labeled-tools
        @remove="clearFilter" @clear="clearFilter">
        <template #search>
          <el-input v-model="query" clearable placeholder="搜索代码、名称或描述" aria-label="搜索 Content" />
        </template>
        <template #filters>
          <el-select v-model="statusFilter" aria-label="Content 状态">
            <el-option label="全部状态" value="ALL" />
            <el-option label="使用中" value="ACTIVE" />
            <el-option label="已归档" value="ARCHIVED" />
          </el-select>
        </template>
      </yp-filter-bar>

      <el-table v-if="visibleItems.length" class="content-table" :data="visibleItems" row-key="id">
        <el-table-column label="Content" min-width="280" fixed="left">
          <template #default="scope">
            <button class="content-name-button" type="button" @click="openWorkspace(contentRow(scope.row))">
              <strong>{{ scope.row.name }}</strong><span>{{ scope.row.code }}</span>
            </button>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="scope"><yp-status-tag domain="content-status" :status="scope.row.status" effect="cell" /></template>
        </el-table-column>
        <el-table-column label="工作项类型" width="140">
          <template #default="scope">{{ workItemLabel(scope.row.workItemType) }}</template>
        </el-table-column>
        <el-table-column label="默认视图" width="130" prop="defaultViewType" />
        <el-table-column label="蓝图来源" min-width="180">
          <template #default="scope">{{ scope.row.appliedBlueprintCode }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="scope">{{ scope.row.updatedAt.toLocaleString('zh-CN') }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="scope">
            <el-button link type="primary" @click="openDrawer(contentRow(scope.row))"><el-icon><edit /></el-icon>配置</el-button>
            <el-button v-if="canWrite" link :type="scope.row.status === 'ACTIVE' ? 'danger' : 'success'"
              :loading="changing === scope.row.id" @click="transition(contentRow(scope.row))">
              {{ scope.row.status === 'ACTIVE' ? '归档' : '恢复' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="visibleItems.length" class="content-cards">
        <article v-for="item in visibleItems" :key="item.id" class="content-card">
          <button type="button" @click="openWorkspace(item)"><strong>{{ item.name }}</strong><span>{{ item.code }}</span></button>
          <yp-status-tag domain="content-status" :status="item.status" effect="soft" />
          <dl><dt>工作项</dt><dd>{{ workItemLabel(item.workItemType) }}</dd><dt>视图</dt><dd>{{ item.defaultViewType }}</dd></dl>
        </article>
      </div>
      <yp-empty-state v-else-if="!loading" reason="no-results"
        :description="items.length ? '没有符合筛选条件的 Content。' : '当前 Project 尚无 Content；Owner 可从固定模板蓝图创建。'" compact />
    </section>

    <el-dialog v-model="createOpen" title="新建 Content" width="min(560px, calc(100vw - 32px))">
      <el-form label-position="top" @submit.prevent="createContent">
        <el-form-item label="蓝图" required>
          <el-select v-model="createForm.blueprintCode" @change="selectBlueprint(catalog?.blueprintOptions.find(item => item.blueprintCode === createForm.blueprintCode))">
            <el-option v-for="option in catalog?.blueprintOptions" :key="option.blueprintCode"
              :label="`${option.displayName} · ${workItemLabel(option.workItemType)}`" :value="option.blueprintCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="代码" required><el-input v-model="createForm.code" maxlength="32" placeholder="例如 REQ_CORE" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="createForm.name" maxlength="80" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="createForm.description" type="textarea" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" :loading="creating" @click="createContent">创建</el-button></template>
    </el-dialog>

    <el-drawer v-model="drawerOpen" :title="selected ? `${selected.name} · ${selected.code}` : 'Content 配置'"
      size="min(720px, 100vw)" destroy-on-close>
      <template v-if="selected && draftConfig">
        <div v-if="latestConflict" class="content-conflict" role="alert">
          <strong>服务器版本已更新，本地草稿仍保留。</strong>
          <span>可载入最新版本，或仅把当前筛选/排序合并进最新版；后者不会覆盖并发列配置或 Kanban 分组。</span>
          <div><el-button @click="loadLatest">载入最新版本</el-button><el-button type="warning" @click="retryQueryMerge">合并查询并重新提交</el-button></div>
        </div>
        <section class="drawer-section content-provenance">
          <h3>身份与来源</h3>
          <dl><dt>工作项类型</dt><dd>{{ workItemLabel(selected.workItemType) }}</dd><dt>蓝图</dt><dd>{{ selected.appliedBlueprintCode }}</dd>
            <dt>模板</dt><dd>{{ selected.appliedTemplateKey }} v{{ selected.appliedTemplateVersion }}</dd><dt>版本</dt><dd>{{ selected.rowVersion }}</dd></dl>
        </section>
        <section class="drawer-section">
          <h3>基本信息</h3>
          <el-form label-position="top">
            <el-form-item label="名称"><el-input v-model="draftName" :disabled="!canWrite || selected.status === 'ARCHIVED'" maxlength="80" /></el-form-item>
            <el-form-item label="描述"><el-input v-model="draftDescription" type="textarea" :disabled="!canWrite || selected.status === 'ARCHIVED'" maxlength="500" /></el-form-item>
            <el-form-item label="默认视图"><el-select v-model="draftViewType" :disabled="!canWrite || selected.status === 'ARCHIVED'">
              <el-option label="Table" :value="ContentViewType.Table" /><el-option label="Kanban" :value="ContentViewType.Kanban" />
            </el-select></el-form-item>
          </el-form>
        </section>
        <section class="drawer-section">
          <h3>Table 列</h3><p>用上移/下移按钮调整顺序；所有操作均可键盘完成。</p>
          <ul class="column-list">
            <li v-for="(column, index) in orderedColumns" :key="column">
              <el-checkbox :model-value="!hiddenColumns.has(column)" :disabled="column === ContentTableColumn.Title || !canWrite || selected.status === 'ARCHIVED'"
                @update:model-value="toggleColumn(column, Boolean($event))">{{ columnLabel(column) }}</el-checkbox>
              <span><el-button :icon="ArrowUp" circle aria-label="上移列" :disabled="index === 0 || !canWrite" @click="moveColumn(index, -1)" />
                <el-button :icon="ArrowDown" circle aria-label="下移列" :disabled="index === orderedColumns.length - 1 || !canWrite" @click="moveColumn(index, 1)" /></span>
            </li>
          </ul>
          <h4>默认筛选</h4>
          <content-table-query-editor
            :model-value="{ filters: draftConfig.table.filters, sort: draftConfig.table.sort }"
            :statuses="workflowStatuses" :members="members"
            :disabled="!canWrite || selected.status === 'ARCHIVED'"
            @update:model-value="updateDraftQuery" />
        </section>
        <section class="drawer-section">
          <div class="drawer-section__heading"><div><h3>Kanban 分组</h3><p>每个模板状态必须且只能出现一次。</p></div>
            <el-button :disabled="!canWrite" @click="resetKanban">重置为模板顺序</el-button></div>
          <div v-for="(group, index) in draftConfig.kanban.statusGroups" :key="index" class="kanban-group-editor">
            <el-input v-model="group.name" :disabled="!canWrite" aria-label="看板分组名称" />
            <el-select v-model="group.statusCodes" multiple :disabled="!canWrite" aria-label="看板分组状态">
              <el-option v-for="status in workflowStatuses" :key="status.statusCode" :label="status.displayName" :value="status.statusCode" />
            </el-select>
            <el-button link type="danger" :disabled="!canWrite" @click="removeKanbanGroup(index)">删除</el-button>
          </div>
          <el-button :disabled="!canWrite" @click="addKanbanGroup">添加分组</el-button>
        </section>
      </template>
      <template #footer>
        <div class="drawer-footer"><el-button v-if="selected && canWrite" :type="selected.status === 'ACTIVE' ? 'danger' : 'success'" @click="transition(selected)">
          {{ selected.status === 'ACTIVE' ? '归档 Content' : '恢复 Content' }}</el-button><span />
          <el-button @click="drawerOpen = false">关闭</el-button><el-button v-if="selected?.status === 'ACTIVE' && canWrite" type="primary" :loading="saving" :disabled="Boolean(latestConflict)" @click="save()">保存</el-button></div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.content-board { overflow: hidden; padding: var(--yp-space-4) 0; background: var(--yp-bg-surface); }
.content-readonly-banner, .content-conflict { margin: var(--yp-space-4) 0; padding: var(--yp-space-4); border: 1px solid var(--yp-border-default); border-radius: var(--yp-radius-md); background: var(--yp-bg-selected); color: var(--yp-text-primary); }
.content-table :deep(.el-table__cell) { padding: 0; height: var(--yp-table-row-height); border-color: var(--yp-border-subtle); }
.content-name-button, .content-card > button { display: flex; width: 100%; flex-direction: column; align-items: flex-start; padding: var(--yp-space-2) var(--yp-space-4); border: 0; color: var(--yp-text-primary); background: transparent; cursor: pointer; text-align: left; }
.content-name-button:hover strong, .content-card > button:hover strong { color: var(--yp-link); }
.content-name-button span, .content-card > button span { color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.content-cards { display: none; }
.drawer-section { padding: var(--yp-space-5) 0; border-bottom: 1px solid var(--yp-border-subtle); }
.drawer-section h3, .drawer-section h4, .drawer-section p { margin: 0 0 var(--yp-space-3); }
.drawer-section p { color: var(--yp-text-secondary); }
.drawer-section__heading, .drawer-footer { display: flex; align-items: center; justify-content: space-between; gap: var(--yp-space-3); }
.content-provenance dl, .content-card dl { display: grid; grid-template-columns: auto 1fr; gap: var(--yp-space-2) var(--yp-space-4); }
.content-provenance dt, .content-card dt { color: var(--yp-text-muted); }
.content-provenance dd, .content-card dd { margin: 0; }
.column-list { margin: 0; padding: 0; list-style: none; }
.column-list li { display: flex; min-height: 44px; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--yp-border-subtle); }
.kanban-group-editor { display: grid; grid-template-columns: minmax(120px, .8fr) minmax(240px, 2fr) auto; gap: var(--yp-space-2); margin-bottom: var(--yp-space-2); }
.content-conflict { display: grid; gap: var(--yp-space-2); border-color: var(--yp-status-yellow); }
.drawer-footer span { flex: 1; }
@media (max-width: 720px) {
  .content-table { display: none; }
  .content-cards { display: grid; gap: var(--yp-space-3); }
  .content-card { display: grid; min-height: 132px; grid-template-columns: 1fr auto; gap: var(--yp-space-3); padding: var(--yp-space-3); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
  .content-card > button { min-height: 44px; padding: 0; }
  .content-card dl { grid-column: 1 / -1; margin: 0; }
  .kanban-group-editor { grid-template-columns: 1fr; }
}
</style>
