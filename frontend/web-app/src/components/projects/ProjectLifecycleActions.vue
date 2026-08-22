<script setup lang="ts">
import {
  GovernanceOverrideAction,
  GovernanceTargetType,
  ProjectLifecycle,
  WorkspaceStatusFilter,
  readCsrfToken,
  type ProjectDetail,
  type SafeBlocker,
  type Workspace,
} from '@yumpoo/api-client'
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption as ElOptionRaw,
  ElSelect as ElSelectRaw,
} from 'element-plus'
import { computed, reactive, ref, type DefineComponent } from 'vue'
import { administrationApi, projectsApi, workspacesApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'

const props = defineProps<{ project: ProjectDetail }>()
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const emit = defineEmits<{
  changed: []
  problem: [problem: ApiProblem]
}>()

const busy = ref(false)
const overrideOpen = ref(false)
const moveOpen = ref(false)
const candidates = ref<Workspace[]>([])
const blockers = ref<SafeBlocker[]>([])
const form = reactive({ reason: '', targetWorkspaceId: '' })

const blockerLabels: Record<string, string> = {
  OPEN_WORK_ITEMS: '未关闭工作项',
  PENDING_WORKLOG_APPROVALS: '待审批工时',
  OPEN_PRODUCT_FEEDBACK: '未关闭产品反馈',
  CURRENT_PROJECTS: '当前项目',
}

const canOperate = computed(() => props.project.capabilities.canArchive
  || props.project.capabilities.canRestore
  || props.project.capabilities.canMoveWorkspace
  || props.project.capabilities.canOverrideArchive)
const validReason = computed(() => form.reason.trim().length >= 10 && form.reason.trim().length <= 500)
const validMove = computed(() => validReason.value && Boolean(form.targetWorkspaceId))

function csrf(): string | undefined {
  const token = readCsrfToken()
  if (!token) emit('problem', localProblem('缺少 CSRF 凭据，请刷新后重试。'))
  return token
}

async function run(operation: (token: string) => Promise<unknown>, success: string): Promise<void> {
  const token = csrf()
  if (!token) return
  busy.value = true
  blockers.value = []
  try {
    await operation(token)
    ElMessage.success(success)
    overrideOpen.value = false
    moveOpen.value = false
    form.reason = ''
    form.targetWorkspaceId = ''
    emit('changed')
  } catch (reason) {
    const problem = await toApiProblem(reason)
    if (isProblemStatus(problem, 409) && problem.kind === 'response') {
      blockers.value = problem.error.details.blockers ?? []
    }
    if (isProblemStatus(problem, 412)) {
      ElMessage.warning('Project 已被其他操作更新，已刷新为最新状态。')
      emit('changed')
    }
    emit('problem', problem)
  } finally {
    busy.value = false
  }
}

async function archive(): Promise<void> {
  try {
    await ElMessageBox.confirm('归档后 Project 只读，可由 CompanyAdmin 恢复。', '归档 Project', {
      type: 'warning', confirmButtonText: '确认归档', cancelButtonText: '取消',
    })
  } catch { return }
  await run((token) => projectsApi.archiveProject({
    projectId: props.project.id, xXSRFTOKEN: token, ifMatch: props.project.etag,
    idempotencyKey: crypto.randomUUID(),
  }), 'Project 已归档')
}

async function restore(): Promise<void> {
  try {
    await ElMessageBox.confirm('恢复前将重新验证 Owner、模板和 Workspace。', '恢复 Project', {
      type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消',
    })
  } catch { return }
  await run((token) => projectsApi.restoreProject({
    projectId: props.project.id, xXSRFTOKEN: token, ifMatch: props.project.etag,
    idempotencyKey: crypto.randomUUID(),
  }), 'Project 已恢复')
}

async function openMove(): Promise<void> {
  try {
    const response = await workspacesApi.listWorkspaces({ status: WorkspaceStatusFilter.Active })
    candidates.value = response.items.filter((workspace) => workspace.id !== props.project.workspaceId)
    form.targetWorkspaceId = candidates.value[0]?.id ?? ''
    form.reason = ''
    moveOpen.value = true
  } catch (reason) {
    emit('problem', await toApiProblem(reason))
  }
}

async function move(): Promise<void> {
  if (!validMove.value) return
  await run((token) => projectsApi.moveProjectWorkspace({
    projectId: props.project.id, xXSRFTOKEN: token, ifMatch: props.project.etag,
    idempotencyKey: crypto.randomUUID(),
    projectWorkspaceMoveRequest: {
      targetWorkspaceId: form.targetWorkspaceId,
      reason: form.reason.trim(),
    },
  }), 'Project 已迁移到目标 Workspace')
}

async function overrideArchive(): Promise<void> {
  if (!validReason.value) return
  await run((token) => administrationApi.createGovernanceOverride({
    xXSRFTOKEN: token,
    ifMatch: props.project.etag,
    idempotencyKey: crypto.randomUUID(),
    governanceOverrideRequest: {
      action: GovernanceOverrideAction.ProjectArchiveWithOpenItems,
      targetType: GovernanceTargetType.Project,
      targetId: props.project.id,
      reason: form.reason.trim(),
    },
  }), 'Project 已通过治理覆盖归档')
}
</script>

<template>
  <section
    v-if="canOperate"
    class="lifecycle-actions"
    aria-labelledby="lifecycle-actions-title"
  >
    <div>
      <h2 id="lifecycle-actions-title">生命周期治理</h2>
      <p>操作会在服务端重新鉴权、校验版本并写入安全审计。</p>
    </div>
    <div class="lifecycle-actions__buttons">
      <el-button
        v-if="project.capabilities.canArchive && project.lifecycle === ProjectLifecycle.Active"
        :loading="busy"
        @click="archive"
      >
        归档 Project
      </el-button>
      <el-button
        v-if="project.capabilities.canOverrideArchive"
        type="danger"
        plain
        :loading="busy"
        @click="form.reason = ''; overrideOpen = true"
      >
        治理覆盖归档
      </el-button>
      <el-button
        v-if="project.capabilities.canMoveWorkspace"
        :loading="busy"
        @click="openMove"
      >
        迁移 Workspace
      </el-button>
      <el-button
        v-if="project.capabilities.canRestore"
        type="primary"
        :loading="busy"
        @click="restore"
      >
        恢复 Project
      </el-button>
    </div>
    <el-alert
      v-if="blockers.length"
      type="warning"
      :closable="false"
      title="普通归档被当前事实阻止"
    >
      <ul class="blocker-list">
        <li
          v-for="blocker in blockers"
          :key="blocker.code"
        >
          {{ blockerLabels[blocker.code] ?? blocker.code }}：{{ blocker.count }}
        </li>
      </ul>
    </el-alert>

    <el-dialog
      v-model="overrideOpen"
      title="治理覆盖归档"
      width="520px"
    >
      <p class="dialog-note">此操作会保存理由、安全前后快照与 blocker 分类计数。</p>
      <el-form label-position="top">
        <el-form-item label="覆盖理由（10–500 字）" required>
          <el-input v-model="form.reason" type="textarea" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="overrideOpen = false">取消</el-button>
        <el-button type="danger" :disabled="!validReason" :loading="busy" @click="overrideArchive">
          确认覆盖归档
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="moveOpen"
      title="迁移 Workspace"
      width="520px"
    >
      <el-form label-position="top">
        <el-form-item label="目标 Workspace" required>
          <el-select v-model="form.targetWorkspaceId" class="full-width" placeholder="选择 ACTIVE Workspace">
            <el-option v-for="workspace in candidates" :key="workspace.id" :label="`${workspace.name} (${workspace.code})`" :value="workspace.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="迁移理由（10–500 字）" required>
          <el-input v-model="form.reason" type="textarea" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <p v-if="!candidates.length" class="dialog-note">没有可迁移的其他 ACTIVE Workspace。</p>
      <template #footer>
        <el-button @click="moveOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!validMove" :loading="busy" @click="move">
          确认迁移
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.lifecycle-actions {
  display: grid;
  gap: var(--yp-space-4);
  margin-bottom: var(--yp-space-5);
  padding: var(--yp-space-5);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}
.lifecycle-actions h2,
.lifecycle-actions p { margin: 0; }
.lifecycle-actions p { margin-top: var(--yp-space-1); color: var(--yp-text-secondary); }
.lifecycle-actions__buttons { display: flex; flex-wrap: wrap; gap: var(--yp-space-2); }
.blocker-list { margin: var(--yp-space-2) 0 0; padding-left: 20px; }
.dialog-note { margin: 0 0 var(--yp-space-4); color: var(--yp-text-secondary); }
.full-width { width: 100%; }
</style>
