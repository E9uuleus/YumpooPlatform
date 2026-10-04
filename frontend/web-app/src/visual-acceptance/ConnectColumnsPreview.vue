<script setup lang="ts">
import { computed, nextTick, provide, reactive, ref, shallowRef } from 'vue'
import { ProjectLifecycle, type ConnectColumn, type ConnectColumnCatalog, type WorkItemConnection, type WorkItemConnectionCell, type WorkItemDetail } from '@yumpoo/api-client'
import ConnectCell from '../components/projects/connect/ConnectCell.vue'
import ConnectColumnAddButton from '../components/projects/connect/ConnectColumnAddButton.vue'
import ConnectColumnHeader from '../components/projects/connect/ConnectColumnHeader.vue'
import ConnectColumnSetupPopover from '../components/projects/connect/ConnectColumnSetupPopover.vue'
import ConnectTableCell from '../components/projects/connect/ConnectTableCell.vue'
import { connectColumnAutoName } from '../components/projects/connect/connectColumnKeys'
import type { ConnectSetupMode, ConnectTargetChoice } from '../components/projects/connect/useConnectTable'
import ConnectColumnDeleteDialog from '../components/projects/connect/ConnectColumnDeleteDialog.vue'
import ConnectedItemCard from '../components/projects/connect/ConnectedItemCard.vue'
import ProjectConnectionsOverview from '../components/projects/connect/ProjectConnectionsOverview.vue'
import ConnectFilterSection from '../components/projects/connect/ConnectFilterSection.vue'
import ConnectKanbanConnections from '../components/projects/connect/ConnectKanbanConnections.vue'
import YpAssignee from '../components/yp/YpAssignee.vue'
import { connectColumnsContext, type ConnectColumns } from '../components/projects/connect/useConnectColumns'
import { workItemLabelColorValue } from '../components/projects/workItemLabelColors'
import { connectedItemSource, type ConnectedItemSource } from '../components/projects/connect/useConnectedItem'
import { previewColumn, previewConnection, previewConnections, previewContents, previewDetail, previewLabels, previewProject, previewSecondProject,
  previewSource, previewTarget } from './connectColumnFixtures'

const role = ref<'member' | 'nonmember' | 'admin'>('member'), view = ref('source')
const readOnly = computed(() => role.value === 'admin')
const baseColumns = ref<ConnectColumn[]>([previewColumn, { ...previewColumn, id: 'preview-multi', name: '跨项目协作', targets: [...previewColumn.targets, previewSecondProject] }])
const columns = computed(() => baseColumns.value.map(column => ({ ...column, targets: column.targets.map(target => ({ ...target, actorCanLinkExisting: role.value === 'member' })) })))
const hidden = ref<string[]>([]), connections = ref(previewConnections)
const filters = reactive({ connectedColumnIds: new Set<string>(), unconnectedColumnIds: new Set<string>(), incomingProjectIds: new Set<string>() })
const incomingOptions = [{ value: previewSecondProject.projectId, label: previewSecondProject.name, count: 2 }]
function changeFilter(field: keyof typeof filters, id: string, checked: boolean) {
  if (checked) filters[field].add(id); else filters[field].delete(id)
  if (checked && field !== 'incomingProjectIds') filters[field === 'connectedColumnIds' ? 'unconnectedColumnIds' : 'connectedColumnIds'].delete(id)
}
const kanbanConnections = computed(() => [...previewConnections, ...previewConnections.slice(0, 2).map((connection, index) => ({ ...connection,
  id: `preview-more-${index}`, columnName: '跨项目协作', target: { ...connection.target, projectId: previewSecondProject.projectId, projectCode: previewSecondProject.code, projectName: previewSecondProject.name } }))].map(projectConnection))
const kanbanCell = computed<WorkItemConnectionCell>(() => ({ workItemId: previewSource.workItemId, incoming: [], incomingTotal: 0, incomingByColumn: [],
  outgoing: [{ columnId: previewColumn.id, connections: kanbanConnections.value }] }))
const card = shallowRef<WorkItemConnection>(), perspective = ref<'source' | 'target'>('source')
const setup = ref<{ id: string; mode: ConnectSetupMode }>(), deleting = shallowRef<ConnectColumn>()
const catalog = computed<ConnectColumnCatalog>(() => ({ items: columns.value, incomingAvailable: true,
  incomingColumns: [{ columnId: 'preview-incoming', columnName: '实施问题', projectId: previewSecondProject.projectId, projectName: previewSecondProject.name, projectCode: previewSecondProject.code, projectLifecycle: ProjectLifecycle.Active, actorCanLinkExisting: true }],
  canManage: !readOnly.value, canDelete: !readOnly.value }))
const rows = [previewSource, { ...previewSource, workItemId: 'preview-source-2', itemNo: 'P012-9', title: '批量导出缺少列', assignee: null },
  { ...previewSource, workItemId: 'preview-source-3', itemNo: 'P012-10', title: '新站点初始化失败' }]
function projectConnection(connection: WorkItemConnection): WorkItemConnection {
  return { ...connection, target: { ...connection.target, canOpen: role.value !== 'nonmember' }, capabilities: { canUnlink: !readOnly.value && connection.capabilities.canUnlink } }
}
function rowConnections(id: string, column: ConnectColumn) {
  if (id !== previewSource.workItemId) return []
  return connections.value.map(connection => projectConnection({ ...connection, columnId: column.id, columnName: column.name,
    target: column.id === 'preview-multi' && connection.id === 'preview-connection-2' ? { ...connection.target, projectId: previewSecondProject.projectId, projectCode: previewSecondProject.code, projectName: previewSecondProject.name } : connection.target }))
}
function openCard(connection: WorkItemConnection, side: 'source' | 'target' = 'source') { perspective.value = side; card.value = projectConnection(connection) }
const tableScroll = ref<HTMLElement>()
function edit(column?: ConnectColumn) {
  if (readOnly.value) return
  setup.value = column ? { id: column.id, mode: 'edit' } : { id: 'draft', mode: 'create' }
  if (!column) void nextTick(() => tableScroll.value?.scrollTo({ left: tableScroll.value.scrollWidth }))
}
async function submitSetup(projects: ConnectTargetChoice[], column?: ConnectColumn) {
  const others = columns.value.filter(item => item.id !== column?.id).map(item => item.name)
  const input = { name: column?.name ?? connectColumnAutoName(projects.map(project => project.name), others), targetProjectIds: new Set(projects.map(project => project.id)) }
  if (column) await service.updateColumn(column, input); else await service.createColumn(input)
  setup.value = undefined
}
const service: ConnectColumns = {
  catalog, cells: shallowRef(new Map()), catalogLoading: ref(false), catalogError: ref(), cellError: ref(),
  loadCatalog: async () => {}, ensureCells: async () => {}, refreshCells: async () => {},
  createColumn: async input => {
    const column = { ...previewColumn, id: crypto.randomUUID(), name: input.name, targets: [previewColumn.targets[0]!, previewSecondProject].filter(target => input.targetProjectIds.has(target.projectId)) }
    baseColumns.value = [...baseColumns.value, column]; return column
  },
  updateColumn: async (column, input) => {
    const updated = { ...column, name: input.name, targets: [previewColumn.targets[0]!, previewSecondProject].filter(target => input.targetProjectIds.has(target.projectId)) }
    baseColumns.value = baseColumns.value.map(current => current.id === column.id ? updated : current); return updated
  },
  deleteColumn: async column => { baseColumns.value = baseColumns.value.filter(current => current.id !== column.id); return connections.value.length },
  link: async () => previewConnection,
  reverseLink: async () => previewConnection,
  reverseCreateAndLink: async () => previewConnection,
  createAndLink: async (_id, input) => {
    const created = { ...previewConnection, id: crypto.randomUUID(), target: { ...previewTarget, title: input.title, assignee: null, priority: null } }
    connections.value = [...connections.value, created]; return created
  },
  unlink: async connection => { connections.value = connections.value.filter(current => current.id !== connection.id) },
  getConnection: async id => projectConnection(card.value?.id === id ? card.value : connections.value.find(connection => connection.id === id) ?? previewConnection),
  searchTargets: async query => ({ items: [previewColumn.targets[0]!, previewSecondProject].map(target => ({ id: target.projectId, code: target.code, name: target.name })).filter(target => `${target.name}${target.code}`.includes(query)), page: 0, size: 20, totalElements: 2, totalPages: 1 }),
  createOptions: async () => ({ targetProjectId: previewTarget.projectId, targetProjectName: previewTarget.projectName, categories: [previewTarget.category], defaultContentId: previewTarget.category.id }),
  searchCandidates: async () => ({ items: [{ card: previewTarget, parent: null, alreadyConnected: true }, { card: { ...previewTarget, title: '打印队列重试机制', workItemId: 'preview-candidate', itemNo: 'P003-55' }, parent: null, alreadyConnected: false }], page: 0, size: 20, totalElements: 2, totalPages: 1 }),
  reverseCandidates: async () => ({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
  reverseCreateOptions: async () => ({ targetProjectId: previewSecondProject.projectId, targetProjectName: previewSecondProject.name, categories: [previewTarget.category], defaultContentId: previewTarget.category.id }),
  incoming: async () => ({ items: [projectConnection(previewConnection)], page: 0, size: 50, totalElements: 1, totalPages: 1 }),
}
provide(connectColumnsContext, service)
// The card edits an in-memory copy so the editable field layout can be reviewed without a backend.
const details = new Map<string, WorkItemDetail>()
const itemSource: ConnectedItemSource = {
  load: async workItemId => {
    const card = [...previewConnections.flatMap(connection => [connection.source, connection.target]), previewTarget]
      .find(value => value.workItemId === workItemId) ?? previewTarget
    const detail = details.get(workItemId) ?? previewDetail(card)
    return { detail, project: previewProject(card), contents: previewContents(card.projectId), labels: previewLabels }
  },
  edit: async (detail, change) => {
    const content = previewContents(detail.projectId).items.find(item => item.id === change.value)
    const status = previewLabels.statuses.find(item => item.code === change.value)
    const updated: WorkItemDetail = { ...detail, rowVersion: detail.rowVersion + 1, etag: `"${detail.rowVersion + 1}"`, updatedAt: new Date(),
      ...(change.field === 'title' ? { title: change.value } : {}),
      ...(change.field === 'priority' ? { priority: change.value as string | null } : {}),
      ...(change.field === 'assignee' ? { assigneeUserId: change.value as string | null, assigneeDisplayName: change.value ? '王五' : null } : {}),
      ...(change.field === 'dueDate' ? { dueDate: change.value as Date | null, dueTime: change.dueTime ?? null } : {}),
      ...(change.field === 'content' && content ? { contentId: content.id, contentName: content.name, contentColorToken: content.colorToken } : {}),
      ...(change.field === 'status' && status ? { statusCode: status.code, statusCategory: status.statusCategory } : {}) }
    details.set(detail.id, updated)
    return updated
  },
}
provide(connectedItemSource, itemSource)
</script>

<template>
  <section
    id="connect-columns"
    class="connect-preview page-stack"
    aria-label="连接列视觉验收"
  >
    <div class="section-heading">
      <div><h2>连接列</h2><p>静态验收数据 · 与正式表格共用连接组件</p></div>
    </div>
    <div class="connect-preview__controls">
      <el-radio-group
        v-model="view"
        aria-label="连接验收页面"
      >
        <el-radio-button value="source">
          来源项目表格
        </el-radio-button><el-radio-button value="target">
          目标项目表格
        </el-radio-button><el-radio-button value="settings">
          设置页连接概览
        </el-radio-button><el-radio-button value="filters">
          连接筛选
        </el-radio-button><el-radio-button value="kanban">
          看板连接
        </el-radio-button>
      </el-radio-group>
      <el-radio-group
        v-model="role"
        aria-label="连接验收身份"
      >
        <el-radio-button value="member">
          双方成员
        </el-radio-button><el-radio-button value="nonmember">
          非目标项目成员
        </el-radio-button><el-radio-button value="admin">
          企业管理员只读
        </el-radio-button>
      </el-radio-group>
    </div>
    <div class="connect-preview__surface">
      <header class="connect-preview__project">
        <div><h3>{{ view === 'target' ? previewTarget.projectName : previewSource.projectName }}</h3><span>{{ view === 'target' ? 'P003' : 'P012' }} · 进行中</span></div><span v-if="readOnly">公司管理员只读</span>
      </header>
      <project-connections-overview
        v-if="view === 'settings'"
        :project-id="previewSource.projectId"
        :catalog="catalog"
      />
      <connect-filter-section
        v-else-if="view === 'filters'"
        class="connect-preview__filters"
        :columns="columns"
        :incoming="incomingOptions"
        :connected-column-ids="filters.connectedColumnIds"
        :unconnected-column-ids="filters.unconnectedColumnIds"
        :incoming-project-ids="filters.incomingProjectIds"
        @change="changeFilter"
      />
      <div v-else-if="view === 'kanban'" class="connect-preview__kanban">
        <section v-for="(status, index) in ['进行中', '待处理']" :key="status" class="connect-preview__lane">
          <h4>{{ status }}</h4>
          <article class="connect-preview__kanban-card">
            <small>{{ index === 0 ? previewSource.itemNo : 'P012-9' }}</small>
            <strong>{{ index === 0 ? previewSource.title : '批量导出缺少列' }}</strong>
            <connect-kanban-connections :cell="index === 0 ? kanbanCell : undefined" @open-card="openCard" />
          </article>
        </section>
      </div>
      <template v-else>
        <div
          ref="tableScroll"
          class="connect-preview__table-scroll"
        >
          <table class="connect-preview__table">
            <thead>
              <tr>
                <th class="connect-preview__title">
                  工作项名称
                </th><th class="connect-preview__assignee">
                  处理人
                </th><th class="connect-preview__status">
                  状态
                </th>
                <template v-if="view === 'source'">
                  <th
                    v-for="column in columns.filter(column => !hidden.includes(column.id))"
                    :key="column.id"
                    class="connect-preview__connection"
                  >
                    <div class="connect-preview__header-host">
                      <connect-column-header
                        :label="column.name"
                        kind="connect"
                        :can-manage="!readOnly"
                        :can-delete="!readOnly"
                        :taken-names="columns.map(item => item.name)"
                        :rename="name => service.updateColumn(column, { name, targetProjectIds: new Set(column.targets.map(target => target.projectId)) })"
                        @edit="edit(column)"
                        @hide="hidden.push(column.id)"
                        @delete="deleting = column"
                      />
                      <connect-column-setup-popover
                        v-if="setup?.id === column.id"
                        :visible="true"
                        mode="edit"
                        :column="column"
                        :project-id="previewSource.projectId"
                        :project-name="previewSource.projectName"
                        :submit="projects => submitSetup(projects, column)"
                        @update:visible="setup = undefined"
                      />
                    </div>
                  </th>
                  <th
                    v-if="setup?.id === 'draft'"
                    class="connect-preview__connection"
                  >
                    <div class="connect-preview__header-host">
                      <connect-column-header
                        label="新连接"
                        kind="draft"
                        :can-manage="false"
                        :can-delete="false"
                      />
                      <connect-column-setup-popover
                        :visible="true"
                        mode="create"
                        :project-id="previewSource.projectId"
                        :project-name="previewSource.projectName"
                        :submit="projects => submitSetup(projects)"
                        @update:visible="setup = undefined"
                      />
                    </div>
                  </th>
                </template>
                <th
                  v-else
                  class="connect-preview__connection"
                >
                  <connect-column-header
                    :label="catalog.incomingColumns[0]!.projectName"
                    kind="reverse"
                    :reverse-hint="`双向连接：来自「${catalog.incomingColumns[0]!.projectName}」的「${catalog.incomingColumns[0]!.columnName}」`"
                    :can-manage="false"
                    :can-delete="false"
                  />
                </th>
                <th class="connect-preview__add">
                  <connect-column-add-button
                    :can-manage="!readOnly"
                    :hidden-columns="columns.filter(column => hidden.includes(column.id)).map(column => ({ key: column.id, label: column.name }))"
                    @add-connect-column="edit()"
                    @show-column="hidden = hidden.filter(id => id !== $event)"
                  />
                </th>
              </tr>
            </thead><tbody>
              <tr
                v-for="(row, index) in (view === 'source' ? rows : [previewTarget, { ...previewTarget, workItemId: 'preview-target-empty', title: '会话续期异常', itemNo: 'P003-44' }])"
                :key="row.workItemId"
              >
                <td class="connect-preview__title">
                  <code>{{ row.itemNo }}</code><span>{{ row.title }}</span>
                </td><td>
                  <yp-assignee
                    v-if="row.assignee"
                    :user-id="row.assignee.userId"
                    :display-name="row.assignee.displayName"
                    size="table"
                  /><span v-else>未分配</span>
                </td><td
                  class="connect-preview__status-value"
                  :style="{ background: workItemLabelColorValue(row.status.colorToken) }"
                >
                  {{ row.status.name }}
                </td>
                <template v-if="view === 'source'">
                  <td
                    v-for="column in columns.filter(column => !hidden.includes(column.id))"
                    :key="column.id"
                  >
                    <connect-cell
                      :key="`${column.id}:${role}`"
                      :item="{ id: row.workItemId, title: row.title }"
                      :column="column"
                      :connections="rowConnections(row.workItemId, column)"
                      :read-only="readOnly"
                      @open-card="openCard($event)"
                    />
                  </td>
                  <td v-if="setup?.id === 'draft'">
                    <connect-table-cell
                      :column="{ key: 'connect-draft', kind: 'draft', label: '新连接', width: 200, minWidth: 140 }"
                      :item="{ id: row.workItemId, title: row.title }"
                      :read-only="true"
                      :row-index="index"
                    />
                  </td>
                </template>
                <td v-else>
                  <connect-cell
                    :key="role"
                    :item="{ id: row.workItemId, title: row.title }"
                    :reverse="catalog.incomingColumns[0]!"
                    :connections="index === 0 ? [projectConnection(previewConnection)] : []"
                    :total="index === 0 ? 1 : 0"
                    :read-only="readOnly"
                    @open-card="openCard($event, 'target')"
                  />
                </td><td />
              </tr>
            </tbody>
          </table>
        </div>
        <p class="connect-preview__hint">
          点击连接标签打开卡片；点击单元格空白或 +N 管理连接。
        </p>
      </template>
    </div>
    <div class="connect-preview__examples">
      <span>卡片状态</span><el-button
        size="small"
        @click="openCard(previewConnection)"
      >
        有处理人
      </el-button><el-button
        size="small"
        @click="openCard(previewConnections[1]!)"
      >
        无处理人 / 无优先级
      </el-button><el-button
        size="small"
        @click="openCard({ ...previewConnections[2]!, target: { ...previewConnections[2]!.target, projectLifecycle: ProjectLifecycle.Archived } })"
      >
        已归档
      </el-button>
    </div>
    <connect-column-delete-dialog
      v-if="deleting"
      :column="deleting"
      @close="deleting = undefined"
    />
    <connected-item-card
      v-if="card"
      :open="Boolean(card)"
      :connection="card"
      :perspective="perspective"
      :read-only="view === 'kanban'"
      @update:open="card = undefined"
    />
  </section>
</template>

<style scoped>
.connect-preview { min-width: 0; scroll-margin-top: 20px; }
.connect-preview__header-host { position: relative; }
.connect-preview .section-heading p { margin: 6px 0 0; color: var(--yp-text-muted); font-size: 12px; }
.connect-preview__controls { display: flex; flex-wrap: wrap; gap: 12px; justify-content: space-between; }
.connect-preview__surface { min-width: 0; padding: 24px; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-raised); }
.connect-preview__project { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 24px; color: var(--yp-text-muted); font-size: 12px; }
.connect-preview__project h3 { margin: 0 0 8px; color: var(--yp-text-primary); font-size: 22px; }
.connect-preview__table-scroll { overflow-x: auto; }
.connect-preview__table { border-collapse: collapse; table-layout: fixed; width: 100%; min-width: 940px; font-size: 13px; }
.connect-preview__table th, .connect-preview__table td { height: 40px; border: 1px solid var(--yp-border-subtle); padding: 0 10px; font-weight: 400; }
.connect-preview__table th { color: var(--yp-text-secondary); background: var(--yp-bg-sunken); }
.connect-preview__table tbody tr:hover { background: var(--yp-bg-hover); }
.connect-preview__title { width: 270px; text-align: left; }
.connect-preview__title code { font-size: 11px; margin-right: 12px; color: var(--yp-text-muted); }
.connect-preview__assignee { width: 90px; }
.connect-preview__status { width: 90px; }
.connect-preview__connection { width: 200px; }
.connect-preview__add { width: 44px; }
.connect-preview__status-value { text-align: center; color: var(--yp-text-inverse); }
.connect-preview__hint { margin: 16px 0 0; color: var(--yp-text-muted); font-size: 12px; }
.connect-preview__examples { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; color: var(--yp-text-secondary); font-size: 12px; }
.connect-preview__examples .el-button { margin-left: 0; }
.connect-preview__filters { max-width: 480px; }
.connect-preview__kanban { display: grid; grid-template-columns: repeat(2, minmax(0, 320px)); gap: var(--yp-space-4); }
.connect-preview__lane { padding: var(--yp-space-3); border-radius: var(--yp-radius-md); background: var(--yp-bg-sunken); }
.connect-preview__lane h4 { margin: 0 0 var(--yp-space-3); }
.connect-preview__kanban-card { display: grid; gap: var(--yp-space-2); padding: var(--yp-space-3); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-raised); }
.connect-preview__kanban-card small { color: var(--yp-text-muted); }
</style>
