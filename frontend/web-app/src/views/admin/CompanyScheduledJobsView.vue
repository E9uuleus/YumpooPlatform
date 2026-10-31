<script setup lang="ts">
import { usePageLoad } from '../../brand/page-progress'

import { readCsrfToken, type ScheduledJob } from '@yumpoo/api-client'
import {
  ElButton,
  ElDialog,
  ElMessage,
  ElOption as ElOptionRaw,
  ElRadioButton,
  ElRadioGroup,
  ElSelect as ElSelectRaw,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTimeSelect,
} from 'element-plus'
import { computed, onMounted, ref, type DefineComponent } from 'vue'
import { useRouter } from 'vue-router'
import { administrationApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import {
  DEFAULT_SCHEDULE,
  describeCron,
  INTERVAL_HOURS,
  parseCron,
  scheduledJobMeta,
  toCron,
  type ScheduleDraft,
} from '../../components/company/scheduledJobPresentation'
import { logTraceLocation } from '../../components/operations/operationsPresentation'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import { useIdentityAdmin } from '../../composables/useIdentityAdmin'
import { useSession } from '../../composables/useSession'
import { formatTimestamp } from '../../design-system/dates'

const pageLoad = usePageLoad()
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent

const router = useRouter(), session = useSession()
const { canWrite } = useIdentityAdmin()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const jobs = ref<ScheduledJob[]>([])
const loading = ref(false)
const error = ref<ApiProblem>()
const savingCode = ref<string>()
const editing = ref<ScheduledJob>()
const dialogOpen = ref(false)
const draft = ref<ScheduleDraft>({ ...DEFAULT_SCHEDULE })

function formatTime(value?: Date | null): string {
  return value ? formatTimestamp(value, timezone.value) : '—'
}

// The runner stamps lastStartedAt on claim and lastFinishedAt on completion; lastOutcome still holds the previous run meanwhile.
function lastState(job: ScheduledJob): string | null {
  if (job.lastStartedAt && (!job.lastFinishedAt || job.lastFinishedAt < job.lastStartedAt)) return 'RUNNING'
  return job.lastOutcome
}

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    jobs.value = (await administrationApi.listScheduledJobs()).items
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function save(job: ScheduledJob, change: { enabled: boolean; cron: string }): Promise<boolean> {
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新页面后重试。')
    return false
  }
  savingCode.value = job.code
  error.value = undefined
  try {
    const updated = await administrationApi.updateScheduledJob({
      jobCode: job.code,
      xXSRFTOKEN: csrf,
      ifMatch: job.etag,
      scheduledJobUpdateRequest: change,
    })
    jobs.value = jobs.value.map(item => item.code === updated.code ? updated : item)
    return true
  } catch (reason) {
    error.value = await toApiProblem(reason)
    return false
  } finally {
    savingCode.value = undefined
  }
}

function toggle(job: ScheduledJob, enabled: boolean): void {
  void save(job, { enabled, cron: job.cron })
}

function openEditor(job: ScheduledJob): void {
  editing.value = job
  draft.value = parseCron(job.cron) ?? { ...DEFAULT_SCHEDULE }
  dialogOpen.value = true
}

async function submitEditor(): Promise<void> {
  if (!editing.value) return
  if (await save(editing.value, { enabled: editing.value.enabled, cron: toCron(draft.value) })) {
    dialogOpen.value = false
    ElMessage.success('调度规则已保存')
  }
}

onMounted(() => pageLoad.track(load))
</script>

<template>
  <section class="scheduled-jobs">
    <inline-problem
      v-if="error"
      :problem="error"
    />

    <div
      v-if="loading || jobs.length"
      class="table-surface table-scroll scheduled-jobs__surface"
    >
      <el-table
        v-loading="loading"
        :data="jobs"
        row-key="code"
      >
        <el-table-column
          label="任务"
          min-width="260"
        >
          <template #default="scope">
            <div class="scheduled-job__name">
              <strong>{{ scheduledJobMeta(scope.row.code).label }}</strong>
              <span>{{ scope.row.available ? scheduledJobMeta(scope.row.code).description : '当前环境未启用该能力，任务不会执行。' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="归属"
          width="120"
        >
          <template #default="scope">
            {{ scheduledJobMeta(scope.row.code).owner }}
          </template>
        </el-table-column>
        <el-table-column
          label="调度规则"
          min-width="160"
        >
          <template #default="scope">
            {{ describeCron(scope.row.cron) }}
          </template>
        </el-table-column>
        <el-table-column
          label="启用"
          width="90"
        >
          <template #default="scope">
            <el-switch
              :model-value="scope.row.enabled"
              :disabled="!canWrite || !scope.row.available"
              :loading="savingCode === scope.row.code"
              :aria-label="`启用${scheduledJobMeta(scope.row.code).label}`"
              @change="value => toggle(scope.row as ScheduledJob, value as boolean)"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="上次运行"
          min-width="220"
        >
          <template #default="scope">
            <div
              v-if="lastState(scope.row as ScheduledJob)"
              class="header-meta"
            >
              <yp-status-tag
                domain="scheduled-job"
                :status="lastState(scope.row as ScheduledJob)!"
                effect="soft"
              />
              <span class="numeric">{{ formatTime(scope.row.lastStartedAt) }}</span>
              <span
                v-if="scope.row.lastErrorCode"
                class="muted-text"
              >{{ scope.row.lastErrorCode }}</span>
            </div>
            <span
              v-else
              class="muted-text"
            >暂无</span>
          </template>
        </el-table-column>
        <el-table-column
          label="下次运行"
          min-width="160"
        >
          <template #default="scope">
            <span class="numeric">{{ scope.row.enabled ? formatTime(scope.row.nextRunAt) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="240"
          fixed="right"
        >
          <template #default="scope">
            <el-button
              v-if="canWrite"
              link
              type="primary"
              :disabled="!scope.row.available"
              @click="openEditor(scope.row as ScheduledJob)"
            >
              编辑规则
            </el-button>
            <el-button
              v-if="scheduledJobMeta(scope.row.code).history"
              link
              type="primary"
              @click="router.push(scheduledJobMeta(scope.row.code).history!)"
            >
              运行记录
            </el-button>
            <el-button
              v-if="session.isPlatformAdmin.value && scope.row.lastRequestId && scope.row.lastStartedAt"
              link
              type="primary"
              @click="router.push(logTraceLocation(scope.row.lastRequestId, scope.row.lastStartedAt, scope.row.lastFinishedAt))"
            >
              运行日志
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <yp-empty-state
      v-else
      description="暂无可用的定时任务。"
    />

    <el-dialog
      v-model="dialogOpen"
      :title="`编辑调度规则 · ${editing ? scheduledJobMeta(editing.code).label : ''}`"
      width="min(460px, 95vw)"
    >
      <div class="schedule-form">
        <el-radio-group
          v-model="draft.kind"
          aria-label="调度方式"
        >
          <el-radio-button value="daily">
            每天定时
          </el-radio-button>
          <el-radio-button value="interval">
            按间隔
          </el-radio-button>
        </el-radio-group>
        <el-time-select
          v-if="draft.kind === 'daily'"
          v-model="draft.time"
          start="00:00"
          end="23:45"
          step="00:15"
          :clearable="false"
          aria-label="执行时间"
        />
        <el-select
          v-else
          v-model="draft.hours"
          aria-label="执行间隔"
        >
          <el-option
            v-for="hours in INTERVAL_HOURS"
            :key="hours"
            :label="`每 ${hours} 小时`"
            :value="hours"
          />
        </el-select>
        <p class="muted-text">
          保存后：{{ describeCron(toCron(draft)) }}（{{ timezone }}）
        </p>
      </div>
      <template #footer>
        <el-button @click="dialogOpen = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="Boolean(savingCode)"
          @click="submitEditor"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.scheduled-jobs {
  display: grid;
  gap: var(--yp-space-4);
}

.scheduled-jobs__surface {
  border-color: var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}

.scheduled-job__name {
  display: grid;
  gap: 2px;
  line-height: 1.4;
}

.scheduled-job__name span {
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}

.schedule-form {
  display: grid;
  gap: var(--yp-space-3);
}

.schedule-form .el-select,
.schedule-form :deep(.el-select) {
  width: 100%;
}

.schedule-form p {
  margin: 0;
}
</style>
