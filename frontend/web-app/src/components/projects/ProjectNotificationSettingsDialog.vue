<script setup lang="ts">
import { Bell, FolderOpened, InfoFilled, MuteNotification, Operation } from '@element-plus/icons-vue'
import {
  ProjectNotificationMode,
  readCsrfToken,
  type ProjectNotificationPreference,
  type ProjectNotificationPreferenceUpdateRequest,
} from '@yumpoo/api-client'
import { ElButton, ElCheckbox, ElDialog, ElIcon, ElMessage, ElTooltip } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'
import { notificationsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../InlineProblem.vue'
import YpChoiceCards from '../yp/YpChoiceCards.vue'
import { PROJECT_NOTIFICATION_CATEGORIES } from './projectNotificationPreference'

const props = defineProps<{ modelValue: boolean; projectId: string; projectName: string }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [preference: ProjectNotificationPreference]
}>()

const modeOptions = [
  { value: ProjectNotificationMode.All, title: '接收全部', description: '接收此项目中与你相关的全部通知', icon: Bell },
  { value: ProjectNotificationMode.Muted, title: '全部静音', description: '不再接收此项目的通知', icon: MuteNotification },
  { value: ProjectNotificationMode.Custom, title: '自定义', description: '只接收你选择的通知类型', icon: Operation },
]
const draft = reactive<ProjectNotificationPreferenceUpdateRequest>({
  mode: ProjectNotificationMode.All,
  mention: true,
  comment: true,
  assigned: true,
  connectionCreated: true,
})
const baseline = ref('')
const loading = ref(false)
const saving = ref(false)
const problem = ref<ApiProblem>()
const dirty = computed(() => Boolean(baseline.value) && JSON.stringify(draft) !== baseline.value)
const customEmpty = computed(() => draft.mode === ProjectNotificationMode.Custom
  && PROJECT_NOTIFICATION_CATEGORIES.every(category => !draft[category.key]))
let sequence = 0

function fill(preference: ProjectNotificationPreference): void {
  Object.assign(draft, {
    mode: preference.mode,
    mention: preference.mention,
    comment: preference.comment,
    assigned: preference.assigned,
    connectionCreated: preference.connectionCreated,
  })
  baseline.value = JSON.stringify(draft)
}

async function load(): Promise<void> {
  const current = ++sequence
  loading.value = true
  problem.value = undefined
  baseline.value = ''
  try {
    const preference = await notificationsApi.getMyProjectNotificationPreference({ projectId: props.projectId })
    if (current === sequence) fill(preference)
  } catch (reason) {
    if (current === sequence) problem.value = await toApiProblem(reason)
  } finally {
    if (current === sequence) loading.value = false
  }
}

async function save(): Promise<void> {
  if (!dirty.value || saving.value) return
  const csrf = readCsrfToken()
  if (!csrf) {
    problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  saving.value = true
  problem.value = undefined
  try {
    const preference = await notificationsApi.updateMyProjectNotificationPreference({
      projectId: props.projectId,
      xXSRFTOKEN: csrf,
      projectNotificationPreferenceUpdateRequest: { ...draft },
    })
    ElMessage.success('通知设置已保存')
    emit('saved', preference)
    emit('update:modelValue', false)
  } catch (reason) {
    problem.value = await toApiProblem(reason)
  } finally {
    saving.value = false
  }
}

function close(done?: () => void): void {
  if (saving.value) return
  done?.()
  emit('update:modelValue', false)
}

watch(() => props.modelValue, open => {
  if (open) void load()
}, { immediate: true })
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="project-notification-dialog"
    modal-class="project-notification-overlay"
    width="min(640px, calc(100vw - 32px))"
    align-center
    append-to-body
    destroy-on-close
    :close-on-press-escape="!saving"
    :show-close="!saving"
    :before-close="close"
  >
    <template #header="{ titleId }">
      <h2
        :id="titleId"
        class="project-notification-dialog__title"
      >
        项目通知
      </h2>
      <p class="project-notification-dialog__project">
        <el-icon aria-hidden="true">
          <folder-opened />
        </el-icon>
        <span>{{ projectName }}</span>
      </p>
    </template>
    <inline-problem
      v-if="problem"
      :problem="problem"
    />
    <div
      v-loading="loading"
      class="project-notification-dialog__body"
    >
      <div class="project-notification-dialog__section-label">
        <h3>我的通知</h3>
        <el-tooltip
          content="只影响你在此项目中此后收到的通知；加入、移出项目及负责人变更通知始终送达。"
          placement="top"
        >
          <span
            class="project-notification-dialog__info"
            tabindex="0"
            aria-label="我的通知说明"
          >
            <el-icon><info-filled /></el-icon>
          </span>
        </el-tooltip>
      </div>
      <yp-choice-cards
        v-model="draft.mode"
        :options="modeOptions"
        label="我的通知"
        :disabled="loading || saving"
      />
      <div
        v-if="draft.mode === ProjectNotificationMode.Custom"
        class="project-notification-dialog__custom"
        role="group"
        aria-label="自定义通知类型"
      >
        <el-checkbox
          v-for="category in PROJECT_NOTIFICATION_CATEGORIES"
          :key="category.key"
          v-model="draft[category.key]"
          :disabled="saving"
        >
          <strong>{{ category.label }}</strong>
          <small>{{ category.description }}</small>
        </el-checkbox>
        <p
          v-if="customEmpty"
          class="project-notification-dialog__hint"
          role="status"
        >
          未选择任何类型，效果等同于全部静音。
        </p>
      </div>
    </div>
    <template #footer>
      <el-button
        text
        :disabled="saving"
        @click="close()"
      >
        取消
      </el-button>
      <el-button
        type="primary"
        :disabled="!dirty || loading"
        :loading="saving"
        @click="save"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.project-notification-overlay) { background: var(--yp-overlay); }
:global(.project-notification-dialog.el-dialog) { padding: var(--yp-space-6) var(--yp-space-6) var(--yp-space-5); border-radius: var(--yp-radius-xl); background: var(--yp-bg-raised); box-shadow: var(--yp-shadow-overlay); }
:global(.project-notification-dialog .el-dialog__header) { padding: 0 0 var(--yp-space-5); }
:global(.project-notification-dialog .el-dialog__footer) { display: flex; justify-content: flex-end; gap: var(--yp-space-2); padding-top: var(--yp-space-6); }

.project-notification-dialog__title {
  margin: 0;
  color: var(--yp-text-primary);
  font: 600 var(--yp-type-section-title-size) / var(--yp-type-section-title-line) var(--yp-font-heading);
}

.project-notification-dialog__project {
  display: flex;
  align-items: center;
  gap: var(--yp-space-2);
  margin: var(--yp-space-1) 0 0;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
}

.project-notification-dialog__body {
  display: grid;
  gap: var(--yp-space-3);
}

.project-notification-dialog__section-label {
  display: flex;
  align-items: center;
  gap: var(--yp-space-1);
}

.project-notification-dialog__section-label h3 {
  margin: 0;
  color: var(--yp-text-primary);
  font: 500 var(--yp-type-card-title-size) / var(--yp-type-card-title-line) var(--yp-font-family);
}

.project-notification-dialog__info {
  display: inline-grid;
  place-items: center;
  border-radius: 50%;
  color: var(--yp-text-muted);
  cursor: help;
}

.project-notification-dialog__info:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 2px;
}

.project-notification-dialog__custom {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--yp-space-3) var(--yp-space-5);
  padding: var(--yp-space-1) var(--yp-space-5) 0 56px;
}

.project-notification-dialog__custom :deep(.el-checkbox) {
  height: auto;
  align-items: flex-start;
  margin-right: 0;
  white-space: normal;
}

.project-notification-dialog__custom :deep(.el-checkbox__input) {
  margin-top: 3px;
}

.project-notification-dialog__custom :deep(.el-checkbox__label) {
  display: grid;
  gap: 2px;
}

.project-notification-dialog__custom strong {
  color: var(--yp-text-primary);
  font-weight: 500;
}

.project-notification-dialog__custom small {
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  line-height: var(--yp-type-caption-line);
}

.project-notification-dialog__hint {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}

@media (max-width: 720px) {
  .project-notification-dialog__custom {
    grid-template-columns: minmax(0, 1fr);
    padding-left: var(--yp-space-5);
  }
}
</style>
