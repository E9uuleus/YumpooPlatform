<script setup lang="ts">
import {
  Bell, Box, CircleCheck, Clock, DataAnalysis, Grid, MoreFilled, RefreshLeft, RefreshRight, Setting, SwitchButton, User,
} from '@element-plus/icons-vue'
import {
  ProjectActorAccess, ProjectLifecycle, readCsrfToken, type ProjectDetail, type ProjectNotificationPreference,
} from '@yumpoo/api-client'
import { ElDropdown, ElDropdownItem as ElDropdownItemRaw, ElDropdownMenu, ElIcon, ElMessage, ElMessageBox } from 'element-plus'
import { computed, ref, type DefineComponent } from 'vue'
import { useRouter } from 'vue-router'
import { notificationsApi, projectsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { notifyProjectLifecycleChanged } from '../../composables/projectLifecycleEvents'
import { useProjectRecents } from '../../composables/useProjectRecents'
import { useSession } from '../../composables/useSession'
import { useTimeTracker } from '../../composables/useTimeTracker'
import ProjectNotificationSettingsDialog from './ProjectNotificationSettingsDialog.vue'
import { projectNotificationSummary } from './projectNotificationPreference'
import { useProjectLifecycleActions } from './useProjectLifecycleActions'

/** 页头的项目级菜单：项目分区导航与项目操作。表格设置菜单与工作项行菜单是另外两个独立的省略号。 */
type Command =
  | { kind: 'route'; route: string }
  | { kind: 'notifications' | 'end' | 'archive' | 'restore' | 'leave' }

const props = defineProps<{ project: ProjectDetail; section: string }>()
const emit = defineEmits<{ changed: [] }>()
const ElDropdownItem = ElDropdownItemRaw as unknown as DefineComponent

const SECTIONS = [
  { section: 'overview', route: 'project-overview', label: '工作项', icon: Grid },
  { section: 'statistics', route: 'project-statistics', label: '统计', icon: DataAnalysis },
  { section: 'members', route: 'project-members', label: '成员', icon: User },
  { section: 'activity', route: 'project-activity', label: '动态', icon: Clock },
  { section: 'settings', route: 'project-settings', label: '设置', icon: Setting },
]

const router = useRouter()
const session = useSession()
const recents = useProjectRecents(() => {
  const authentication = session.authentication.value
  return authentication ? `${authentication.company.id}:${authentication.user.id}` : undefined
})
const tracker = useTimeTracker()
const notificationsOpen = ref(false)
const preference = ref<ProjectNotificationPreference>()
const leaving = ref(false)
const lifecycle = useProjectLifecycleActions(() => props.project, {
  changed: () => emit('changed'),
  problem: problem => ElMessage.error(problemMessage(problem)),
})
const capabilities = computed(() => props.project.capabilities)
const canConfigureNotifications = computed(() => props.project.actorAccess !== ProjectActorAccess.CompanyAdmin)
const canArchive = computed(() => capabilities.value.canArchive && props.project.lifecycle === ProjectLifecycle.Active)
const hasActions = computed(() => canConfigureNotifications.value || capabilities.value.canEnd || capabilities.value.canResume
  || canArchive.value || capabilities.value.canRestore || Boolean(capabilities.value.canLeave))

async function loadPreference(visible: boolean): Promise<void> {
  if (!visible || !canConfigureNotifications.value) return
  const id = props.project.id
  try {
    const value = await notificationsApi.getMyProjectNotificationPreference({ projectId: id })
    if (id === props.project.id) preference.value = value
  } catch {
    preference.value = undefined
  }
}

async function leave(): Promise<void> {
  const project = props.project
  try {
    await ElMessageBox.confirm(
      `退出后你将无法访问「${project.name}」，你在本项目中正在运行的计时会停止。已记录的工时、讨论和处理人记录会保留；需要重新加入时请联系项目负责人。`,
      '退出项目',
      { type: 'warning', confirmButtonText: '确认退出', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' },
    )
  } catch { return }
  const token = readCsrfToken()
  if (!token) { ElMessage.error('缺少 CSRF 凭据，请刷新后重试。'); return }
  leaving.value = true
  try {
    await projectsApi.leaveProject({ projectId: project.id, xXSRFTOKEN: token, idempotencyKey: crypto.randomUUID() })
  } catch (reason) {
    ElMessage.error(problemMessage(await toApiProblem(reason)))
    return
  } finally {
    leaving.value = false
  }
  ElMessage.success(`已退出项目「${project.name}」`)
  recents.reconcile([], [project.id])
  notifyProjectLifecycleChanged(project.id)
  void tracker.refresh()
  void router.push({ name: 'workspace-entry' })
}

function run(command: Command): void {
  if (command.kind === 'route') void router.push({ name: command.route, params: { projectId: props.project.id } })
  else if (command.kind === 'notifications') notificationsOpen.value = true
  else if (command.kind === 'end') void lifecycle.toggleEnded()
  else if (command.kind === 'archive') void lifecycle.archive()
  else if (command.kind === 'restore') void lifecycle.restore()
  else void leave()
}
</script>

<template>
  <div class="project-more-menu">
    <el-dropdown
      trigger="click"
      placement="bottom-end"
      :disabled="lifecycle.busy.value || leaving"
      @command="run"
      @visible-change="loadPreference"
    >
      <button
        type="button"
        class="project-more-menu__trigger"
        aria-label="项目菜单"
        data-tour="project-sections"
      >
        <el-icon aria-hidden="true">
          <more-filled />
        </el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu class="project-more-menu__list">
          <li
            class="yp-menu-label"
            role="presentation"
          >
            项目页面
          </li>
          <el-dropdown-item
            v-for="item in SECTIONS"
            :key="item.section"
            :icon="item.icon"
            :command="{ kind: 'route', route: item.route }"
            :class="{ 'yp-menu-item--current': item.section === section }"
            :aria-current="item.section === section ? 'page' : undefined"
          >
            {{ item.label }}
          </el-dropdown-item>
          <li
            v-if="hasActions"
            class="yp-menu-label"
            role="presentation"
          >
            项目操作
          </li>
          <el-dropdown-item
            v-if="canConfigureNotifications"
            :icon="Bell"
            :command="{ kind: 'notifications' }"
          >
            通知提醒<span
              v-if="preference"
              class="yp-menu-value"
            >{{ projectNotificationSummary(preference) }}</span>
          </el-dropdown-item>
          <el-dropdown-item
            v-if="capabilities.canEnd || capabilities.canResume"
            :icon="project.endedAt ? RefreshRight : CircleCheck"
            :command="{ kind: 'end' }"
          >
            {{ project.endedAt ? '重新打开项目' : '结束项目' }}
          </el-dropdown-item>
          <el-dropdown-item
            v-if="canArchive"
            :icon="Box"
            class="yp-menu-item--danger"
            :command="{ kind: 'archive' }"
          >
            归档项目
          </el-dropdown-item>
          <el-dropdown-item
            v-if="capabilities.canRestore"
            :icon="RefreshLeft"
            :command="{ kind: 'restore' }"
          >
            恢复项目
          </el-dropdown-item>
          <el-dropdown-item
            v-if="capabilities.canLeave"
            :icon="SwitchButton"
            class="yp-menu-item--danger"
            :command="{ kind: 'leave' }"
          >
            退出项目
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <project-notification-settings-dialog
      v-if="canConfigureNotifications"
      v-model="notificationsOpen"
      :project-id="project.id"
      :project-name="project.name"
      @saved="preference = $event"
    />
  </div>
</template>

<style scoped>
.project-more-menu { display: inline-flex; }

.project-more-menu__trigger {
  display: inline-grid;
  width: 36px;
  height: 36px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-secondary);
  background: transparent;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard), background-color var(--yp-motion-fast) var(--yp-ease-standard);
}

.project-more-menu__trigger:hover,
.project-more-menu__trigger[aria-expanded='true'] {
  color: var(--yp-text-primary);
  background: var(--yp-bg-hover);
}

.project-more-menu__trigger:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 1px;
}

.project-more-menu__trigger .el-icon { font-size: 18px; }

.project-more-menu__list { min-width: 220px; }
</style>
