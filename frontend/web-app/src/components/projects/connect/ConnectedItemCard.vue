<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { ElButton, ElDialog, ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon, ElInput, ElMessage, ElMessageBox, ElPopover } from 'element-plus'
import { Calendar, Close as CloseIcon, CollectionTag, Flag, List, MoreFilled, Refresh as RefreshIcon, User, UserFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import type { WorkItemConnection, WorkItemDetail, WorkItemLabelColorToken } from '@yumpoo/api-client'
import { isProblemStatus, type ApiProblem } from '../../../api/problems'
import { useSession } from '../../../composables/useSession'
import { formatRelativeTime, formatTimestamp } from '../../../design-system/dates'
import InlineProblem from '../../InlineProblem.vue'
import { workItemAssignees } from '../workItemAssignees'
import YpAssigneeStack from '../../yp/YpAssigneeStack.vue'
import YpAssignee from '../../yp/YpAssignee.vue'
import WorkItemCellActivityLog from '../../collaboration/WorkItemCellActivityLog.vue'
import WorkItemAssigneePicker from '../WorkItemAssigneePicker.vue'
import WorkItemContentPopoverContent from '../WorkItemContentPopoverContent.vue'
import WorkItemDueDateCell from '../WorkItemDueDateCell.vue'
import WorkItemLabelPopoverContent from '../WorkItemLabelPopoverContent.vue'
import { workItemLabelColorValue } from '../workItemLabelColors'
import type { DueDateValue } from '../workItemDueDate'
import { toConnectProblem } from './connectProblems'
import { useConnectContext } from './useConnectColumns'
import { useConnectedItem } from './useConnectedItem'

/**
 * Card for the other end of a connection. Members of that project see and edit its fields through the ordinary work item
 * commands (recorded in its activity); everyone else keeps the server's minimal connection card. `readOnly` only removes
 * connection management (for example on the kanban), never field permissions.
 */
const props = defineProps<{ open: boolean; connection: WorkItemConnection; perspective: 'source' | 'target'; readOnly?: boolean }>()
const emit = defineEmits<{ 'update:open': [open: boolean]; unlinked: [connection: WorkItemConnection]; invalidated: [connection: WorkItemConnection]
  changed: [connection: WorkItemConnection] }>()
const context = useConnectContext(), router = useRouter(), session = useSession()
const current = shallowRef(props.connection), refreshing = ref(false), busy = ref(false), problem = ref<ApiProblem>()
const tab = ref<'fields' | 'activity'>('fields'), activityKey = ref(0), field = ref<string>()
const editingTitle = ref(false), draftTitle = ref(''), titleInput = ref<InstanceType<typeof ElInput>>()
const card = computed(() => props.perspective === 'source' ? current.value.target : current.value.source)
const item = useConnectedItem({ changed: () => { activityKey.value++; emit('changed', current.value) } })
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'UTC')
const detail = computed(() => card.value.available === false ? undefined : item.detail.value)
const full = computed(() => Boolean(detail.value))
const title = computed(() => detail.value?.title ?? card.value.title)
const overview = computed(() => ({ path: `/projects/${card.value.projectId}/overview`, query: { view: 'table' } }))
const statusLabel = computed(() => {
  if (!detail.value) return card.value.status
  const status = item.statuses.value.find(value => value.code === detail.value!.statusCode)
  return { name: status?.displayName ?? detail.value.statusCode, colorToken: status?.colorToken ?? card.value.status.colorToken }
})
const priorityLabel = computed(() => {
  if (!detail.value) return card.value.priority
  const priority = item.priorities.value.find(value => value.code === detail.value!.priority)
  return priority ? { name: priority.displayName, colorToken: priority.colorToken } : null
})
const category = computed(() => detail.value ? { name: detail.value.contentName, colorToken: detail.value.contentColorToken } : card.value.category)
const assignees = computed(() => detail.value ? workItemAssignees(detail.value) : card.value.assignee ? [card.value.assignee] : [])
const connectedAt = computed(() => formatTimestamp(current.value.createdAt, timezone.value))
let controller: AbortController | undefined, revision = 0

async function refresh() {
  controller?.abort(); controller = new AbortController()
  const requestRevision = ++revision
  refreshing.value = true; problem.value = undefined
  try {
    const result = await context.getConnection(props.connection.id, controller.signal)
    if (requestRevision !== revision) return
    if (!result.active) { missing(); return }
    current.value = result
  } catch (reason) {
    const failure = await toConnectProblem(reason)
    if (requestRevision !== revision) return
    if (isProblemStatus(failure, 404)) missing(); else problem.value = failure
  } finally { if (requestRevision === revision) refreshing.value = false }
}
function missing(refreshRows = true) {
  ElMessage.info('该连接已不存在')
  if (refreshRows) emit('invalidated', props.connection)
  emit('update:open', false)
}
watch([() => props.open, () => props.connection.id], () => {
  revision++; controller?.abort(); item.reset(); tab.value = 'fields'; field.value = undefined; editingTitle.value = false
  if (!props.open) return
  current.value = props.connection
  void refresh()
}, { immediate: true })
watch([() => props.open, () => card.value.workItemId, () => card.value.canOpen, () => card.value.available], () => {
  if (card.value.available === false) { item.reset(); editingTitle.value = false; field.value = undefined; tab.value = 'fields'; return }
  if (props.open && card.value.canOpen && item.detail.value?.id !== card.value.workItemId) void item.load(card.value.workItemId)
}, { immediate: true })
onBeforeUnmount(() => { revision++; controller?.abort() })

function close() { if (!busy.value && !item.savingTitle.value) emit('update:open', false) }
async function openWorkItem() {
  if (!card.value.canOpen) return
  await router.push({ ...overview.value, query: { ...overview.value.query, workItemId: card.value.workItemId } })
  close()
}
async function unlink() {
  if (busy.value || props.readOnly || !current.value.capabilities.canUnlink) return
  try { await ElMessageBox.confirm('解除连接后两个工作项都会保留。', '解除连接？', { confirmButtonText: '解除连接', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  busy.value = true; problem.value = undefined
  try { await context.unlink(current.value); emit('unlinked', current.value); emit('update:open', false); ElMessage.success('已解除连接') }
  catch (reason) { problem.value = await toConnectProblem(reason); if (isProblemStatus(problem.value, 404)) missing(false) }
  finally { busy.value = false }
}
function command(action: string) { if (action === 'open') void openWorkItem(); else if (action === 'unlink') void unlink() }
function startTitle() {
  if (card.value.available === false || !item.editable.value) return
  draftTitle.value = title.value; editingTitle.value = true
  void nextTick(() => titleInput.value?.focus())
}
async function commitTitle() {
  if (!editingTitle.value) return
  if (!draftTitle.value.trim() || draftTitle.value.trim() === title.value) { editingTitle.value = false; return }
  if (await item.saveTitle(draftTitle.value)) editingTitle.value = false
}
async function choose(action: () => Promise<boolean>) { field.value = undefined; await action() }
function changeDueDate(value: DueDateValue) {
  void item.patch('dueDate', value.dueDate ? new Date(`${value.dueDate}T00:00:00.000Z`) : null, value.dueTime)
}
const tone = (token?: WorkItemLabelColorToken | null) => token ? { background: workItemLabelColorValue(token) } : undefined
const updatedText = (value: WorkItemDetail) => formatRelativeTime(value.updatedAt)
</script>

<template>
  <el-dialog
    :model-value="open"
    class="connected-item-card"
    width="min(600px, calc(100vw - 32px))"
    top="8vh"
    append-to-body
    destroy-on-close
    :show-close="false"
    :close-on-press-escape="!busy && !editingTitle"
    :before-close="close"
    @update:model-value="close"
  >
    <template #header="{ titleId }">
      <div class="connected-item-card__header">
        <div class="connected-item-card__heading">
          <el-input
            v-if="editingTitle"
            ref="titleInput"
            v-model="draftTitle"
            class="connected-item-card__title-input"
            maxlength="300"
            :disabled="item.savingTitle.value"
            aria-label="工作项标题"
            @keydown.enter.prevent="commitTitle"
            @keydown.esc.stop.prevent="editingTitle = false"
            @blur="commitTitle"
          />
          <h2
            v-else
            :id="titleId"
            :class="{ 'is-editable': item.editable.value }"
            :title="item.editable.value ? '点击编辑标题' : title"
            :tabindex="item.editable.value ? 0 : undefined"
            @click="startTitle"
            @keydown.enter.prevent="startTitle"
          >
            {{ title }}
          </h2>
          <p class="connected-item-card__project">
            <span>所在 →</span>
            <router-link
              v-if="card.canOpen"
              :to="overview"
              @click="close"
            >
              {{ card.projectName }}
            </router-link><span v-else>{{ card.projectName }}</span>
            <code>{{ card.itemNo }}</code>
            <span
              v-if="card.archived"
              class="connected-item-card__archived"
            >已归档</span>
          </p>
        </div>
        <div class="connected-item-card__actions">
          <el-dropdown
            v-if="card.canOpen || (!readOnly && current.capabilities.canUnlink)"
            trigger="click"
            @command="command"
          >
            <button
              type="button"
              class="connected-item-card__icon"
              aria-label="更多操作"
            >
              <el-icon><more-filled /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-if="card.canOpen"
                  command="open"
                >
                  打开工作项
                </el-dropdown-item>
                <el-dropdown-item
                  v-if="!readOnly && current.capabilities.canUnlink"
                  command="unlink"
                  :disabled="busy"
                  :divided="card.canOpen"
                >
                  解除连接
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <button
            type="button"
            class="connected-item-card__icon"
            aria-label="关闭"
            @click="close"
          >
            <el-icon><close-icon /></el-icon>
          </button>
        </div>
      </div>
    </template>
    <inline-problem
      v-if="problem ?? item.problem.value"
      :problem="(problem ?? item.problem.value)!"
    />
    <el-button
      v-if="problem"
      text
      :icon="RefreshIcon"
      @click="refresh"
    >
      重新加载卡片
    </el-button>
    <div
      v-if="full"
      class="connected-item-card__tabs"
      role="tablist"
      aria-label="工作项内容"
    >
      <button
        type="button"
        role="tab"
        :aria-selected="tab === 'fields'"
        @click="tab = 'fields'"
      >
        字段
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="tab === 'activity'"
        @click="tab = 'activity'"
      >
        动态
      </button>
    </div>
    <p
      v-else-if="card.available === false"
      class="connected-item-card__notice"
    >
      连接对端已不可访问，恢复项目后可重新查看。
    </p>
    <p
      v-else-if="!card.canOpen || item.forbidden.value"
      class="connected-item-card__notice"
    >
      你无权访问「{{ card.projectName }}」，仅显示连接摘要。
    </p>
    <dl
      v-if="card.available !== false && (tab === 'fields' || !full)"
      class="connected-item-card__fields"
      :aria-busy="refreshing || item.loading.value"
    >
      <div class="connected-item-card__row">
        <dt>
          <span class="connected-item-card__glyph connected-item-card__glyph--category"><el-icon><collection-tag /></el-icon></span>工作项类别
        </dt>
        <dd>
          <el-popover
            v-if="full && item.editable.value"
            :visible="field === 'content'"
            placement="bottom"
            width="auto"
            popper-class="work-items-label-popover content-popover"
            @update:visible="value => field = value ? 'content' : undefined"
          >
            <template #reference>
              <button
                type="button"
                class="connected-item-card__tile is-filled is-editable"
                :style="tone(category.colorToken)"
                @click="field = field === 'content' ? undefined : 'content'"
              >
                {{ category.name }}
              </button>
            </template>
            <work-item-content-popover-content
              :project-id="card.projectId"
              :catalog="item.contents.value"
              :current-value="detail?.contentId"
              @select="value => choose(() => item.patch('content', value))"
            />
          </el-popover>
          <span
            v-else
            class="connected-item-card__tile is-filled"
            :style="tone(category.colorToken)"
          >{{ category.name }}</span>
        </dd>
      </div>
      <div class="connected-item-card__row">
        <dt>
          <span class="connected-item-card__glyph connected-item-card__glyph--assignee"><el-icon><user /></el-icon></span>处理人
        </dt>
        <dd>
          <el-popover
            v-if="full && item.editable.value"
            :visible="field === 'assignee'"
            placement="bottom"
            :width="300"
            popper-class="work-items-popover"
            @update:visible="value => field = value ? 'assignee' : undefined"
          >
            <template #reference>
              <button
                type="button"
                class="connected-item-card__tile is-editable"
                @click="field = field === 'assignee' ? undefined : 'assignee'"
              >
                <yp-assignee
                  v-if="assignees.length === 1"
                  :user-id="assignees[0]!.userId"
                  :display-name="assignees[0]!.displayName"
                  size="table"
                  show-name
                /><yp-assignee-stack
                  v-else-if="assignees.length > 1"
                  :assignees="assignees"
                /><span v-else>未分配</span>
              </button>
            </template>
            <work-item-assignee-picker
              v-if="field === 'assignee'"
              :project-id="card.projectId"
              :selected="assignees"
              :busy="Boolean(item.busy.value)"
              @change="value => void item.patch('assignees', value)"
            />
          </el-popover>
          <span
            v-else
            class="connected-item-card__tile"
          ><yp-assignee
            v-if="assignees.length === 1"
            :user-id="assignees[0]!.userId"
            :display-name="assignees[0]!.displayName"
            size="table"
            show-name
          /><yp-assignee-stack
            v-else-if="assignees.length > 1"
            :assignees="assignees"
          /><span v-else>未分配</span></span>
        </dd>
      </div>
      <div
        v-if="detail"
        class="connected-item-card__row"
      >
        <dt>
          <span class="connected-item-card__glyph connected-item-card__glyph--due"><el-icon><calendar /></el-icon></span>截止日期
        </dt>
        <dd class="connected-item-card__tile connected-item-card__due">
          <work-item-due-date-cell
            :item="detail"
            :can-edit="item.editable.value"
            :busy="Boolean(item.busy.value)"
            @change="changeDueDate"
          />
        </dd>
      </div>
      <div class="connected-item-card__row">
        <dt>
          <span class="connected-item-card__glyph connected-item-card__glyph--status"><el-icon><list /></el-icon></span>状态
        </dt>
        <dd>
          <el-popover
            v-if="full && item.editable.value"
            :visible="field === 'status'"
            placement="bottom"
            width="auto"
            popper-class="work-items-label-popover status-popover"
            @update:visible="value => field = value ? 'status' : undefined"
          >
            <template #reference>
              <button
                type="button"
                class="connected-item-card__tile is-filled is-editable"
                :style="tone(statusLabel.colorToken)"
                @click="field = field === 'status' ? undefined : 'status'"
              >
                {{ statusLabel.name }}
              </button>
            </template>
            <work-item-label-popover-content
              kind="status"
              :project-id="card.projectId"
              :catalog="item.labels.value"
              :workflow-statuses="item.statuses.value.filter(status => status.active || status.code === detail?.statusCode)"
              :current-value="detail?.statusCode"
              :can-manage="false"
              :available-transitions="detail?.capabilities.availableTransitions"
              @select-status="value => choose(() => item.transition(value))"
            />
          </el-popover>
          <span
            v-else
            class="connected-item-card__tile is-filled"
            :style="tone(statusLabel.colorToken)"
          >{{ statusLabel.name }}</span>
        </dd>
      </div>
      <div class="connected-item-card__row">
        <dt>
          <span class="connected-item-card__glyph connected-item-card__glyph--priority"><el-icon><flag /></el-icon></span>优先级
        </dt>
        <dd>
          <el-popover
            v-if="full && item.editable.value"
            :visible="field === 'priority'"
            placement="bottom"
            width="auto"
            popper-class="work-items-label-popover priority-popover"
            @update:visible="value => field = value ? 'priority' : undefined"
          >
            <template #reference>
              <button
                type="button"
                class="connected-item-card__tile is-editable"
                :class="{ 'is-filled': priorityLabel }"
                :style="tone(priorityLabel?.colorToken)"
                @click="field = field === 'priority' ? undefined : 'priority'"
              >
                {{ priorityLabel?.name ?? '—' }}
              </button>
            </template>
            <work-item-label-popover-content
              kind="priority"
              :project-id="card.projectId"
              :catalog="item.labels.value"
              :priority-options="item.priorities.value"
              :current-value="detail?.priority"
              :can-manage="false"
              @select-priority="value => choose(() => item.patch('priority', value))"
            />
          </el-popover>
          <span
            v-else
            class="connected-item-card__tile"
            :class="{ 'is-filled': priorityLabel }"
            :style="tone(priorityLabel?.colorToken)"
          >{{ priorityLabel?.name ?? '—' }}</span>
        </dd>
      </div>
      <template v-if="detail">
        <div class="connected-item-card__row">
          <dt>
            <span class="connected-item-card__glyph connected-item-card__glyph--updated"><el-icon><refresh-icon /></el-icon></span>最后更新
          </dt>
          <dd class="connected-item-card__tile is-static">
            <yp-assignee
              v-if="detail.updatedByUserId"
              :user-id="detail.updatedByUserId"
              :display-name="detail.updatedByDisplayName ?? ''"
              :show-name="false"
              size="table"
            />
            <span :title="formatTimestamp(detail.updatedAt, timezone)">{{ updatedText(detail) }}</span>
          </dd>
        </div>
        <div class="connected-item-card__row">
          <dt>
            <span class="connected-item-card__glyph connected-item-card__glyph--reporter"><el-icon><user-filled /></el-icon></span>报告人
          </dt>
          <dd class="connected-item-card__tile is-static">
            <yp-assignee
              :user-id="detail.reporterUserId"
              :display-name="detail.reporterDisplayName"
              size="table"
              show-name
            />
          </dd>
        </div>
      </template>
    </dl>
    <work-item-cell-activity-log
      v-else-if="full"
      :key="activityKey"
      class="connected-item-card__activity"
      :work-item-id="card.workItemId"
    />
    <p class="connected-item-card__meta">
      连接于「{{ current.columnName }}」· {{ current.createdBy.displayName }} · {{ connectedAt }}
    </p>
  </el-dialog>
</template>

<style scoped>
.connected-item-card__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.connected-item-card__heading { display: grid; flex: 1; gap: 6px; min-width: 0; }
.connected-item-card__heading h2 { margin: 0; padding: 2px 6px; border: 1px solid transparent; border-radius: var(--yp-radius-sm); color: var(--yp-text-primary); font-family: var(--yp-font-heading); font-size: 22px; font-weight: 600; line-height: 1.4; overflow-wrap: anywhere; margin-left: -7px; }
.connected-item-card__heading h2.is-editable { cursor: text; }
.connected-item-card__heading h2.is-editable:hover { border-color: var(--yp-input-border); }
.connected-item-card__heading h2:focus-visible { outline: none; border-color: var(--yp-input-border-focus); }
.connected-item-card__title-input :deep(.el-input__wrapper) { min-height: 40px; }
.connected-item-card__title-input :deep(.el-input__inner) { font-family: var(--yp-font-heading); font-size: 20px; font-weight: 600; }
.connected-item-card__project { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 0; color: var(--yp-text-secondary); font-size: 13px; }
.connected-item-card__project a { color: var(--yp-link); text-decoration: none; }
.connected-item-card__project a:hover { text-decoration: underline; }
.connected-item-card__project code { color: var(--yp-text-muted); font-size: 12px; }
.connected-item-card__archived { padding: 1px 6px; border-radius: var(--yp-radius-xs); background: var(--yp-bg-sunken); color: var(--yp-text-muted); font-size: 12px; }
.connected-item-card__actions { display: flex; gap: 4px; }
.connected-item-card__icon { display: inline-grid; width: 32px; height: 32px; place-items: center; padding: 0; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-secondary); cursor: pointer; font-size: 16px; }
.connected-item-card__icon:hover { background: var(--yp-bg-hover); color: var(--yp-text-primary); }
.connected-item-card__tabs { display: flex; gap: 20px; margin: -4px 0 12px; border-bottom: 1px solid var(--yp-border-subtle); }
.connected-item-card__tabs button { padding: 8px 0; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--yp-text-secondary); font: inherit; font-size: 14px; cursor: pointer; }
.connected-item-card__tabs button[aria-selected="true"] { border-bottom-color: var(--yp-action-primary); color: var(--yp-text-primary); font-weight: 500; }
.connected-item-card__notice { margin: 0 0 12px; padding: 10px 12px; border-radius: var(--yp-radius-sm); background: var(--yp-bg-sunken); color: var(--yp-text-secondary); font-size: 12px; }
.connected-item-card__fields { display: grid; gap: 8px; margin: 0; }
.connected-item-card__row { display: grid; grid-template-columns: 150px minmax(0, 1fr); align-items: center; gap: 16px; }
.connected-item-card__row dt { display: flex; align-items: center; gap: 10px; color: var(--yp-text-secondary); font-size: 14px; }
.connected-item-card__row dd { min-width: 0; margin: 0; }
.connected-item-card__glyph { display: inline-grid; width: 24px; height: 24px; flex-shrink: 0; place-items: center; border-radius: var(--yp-radius-sm); color: var(--yp-text-inverse); font-size: 14px; }
.connected-item-card__glyph--category { background: var(--yp-label-egg-yolk); }
.connected-item-card__glyph--assignee { background: var(--yp-label-chili-blue); }
.connected-item-card__glyph--due { background: var(--yp-label-purple); }
.connected-item-card__glyph--status { background: var(--yp-label-green); }
.connected-item-card__glyph--priority { background: var(--yp-label-orange); }
.connected-item-card__glyph--updated { background: var(--yp-label-lavender); }
.connected-item-card__glyph--reporter { background: var(--yp-label-teal); }
.connected-item-card__tile { display: flex; width: 100%; min-width: 0; height: 40px; box-sizing: border-box; align-items: center; justify-content: center; gap: 8px; padding: 0 12px; border: 0; border-radius: var(--yp-radius-sm); background: var(--yp-bg-sunken); color: var(--yp-text-secondary); font: inherit; font-size: 14px; white-space: nowrap; transition: background-color var(--yp-motion-fast) var(--yp-ease-standard), filter var(--yp-motion-fast) var(--yp-ease-standard); }
.connected-item-card__tile.is-filled { color: var(--yp-text-inverse); font-weight: 500; }
.connected-item-card__tile.is-editable { cursor: pointer; }
.connected-item-card__tile.is-editable:not(.is-filled):hover { background: var(--yp-bg-hover); }
.connected-item-card__tile.is-filled.is-editable:hover { filter: brightness(1.06); }
.connected-item-card__tile.is-editable:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: 2px; }
.connected-item-card__due { padding: 0; }
.connected-item-card__due :deep(.work-item-due-date) { width: 100%; height: 100%; }
.connected-item-card__activity { max-height: 420px; overflow-y: auto; }
.connected-item-card__meta { margin: 16px 0 0; color: var(--yp-text-muted); font-size: 12px; line-height: 1.7; }
@media (max-width: 520px) { .connected-item-card__row { grid-template-columns: 112px minmax(0, 1fr); gap: 8px; } }
</style>

<style>
.connected-item-card.el-dialog { padding: 20px 24px 22px; border-radius: var(--yp-radius-xl); }
.connected-item-card .el-dialog__header { padding: 0 0 12px; }
</style>
