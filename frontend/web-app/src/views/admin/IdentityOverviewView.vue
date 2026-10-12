<script setup lang="ts">
import { AlarmClock, FolderOpened, OfficeBuilding, Refresh, Tickets, User } from '@element-plus/icons-vue'
import {
  EmploymentStatus,
  ProjectLifecycleFilter,
  type Company,
  type ScheduledJob,
  type WeComIntegrationStatus,
} from '@yumpoo/api-client'
import { ElAlert, ElIcon, ElSkeleton } from 'element-plus'
import { computed, onMounted, ref, watch, type Component } from 'vue'
import { useRouter } from 'vue-router'
import { administrationApi, identityAdministrationApi, projectsApi } from '../../api/client'
import { toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpDefinitionList, { type DefinitionItem } from '../../components/yp/YpDefinitionList.vue'
import YpJumpCard, { type JumpTone } from '../../components/yp/YpJumpCard.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import YpSurface from '../../components/yp/YpSurface.vue'
import { useSession } from '../../composables/useSession'
import { formatRelativeTime, formatTimestamp } from '../../design-system/dates'
import { usePageOnboarding } from '../../onboarding/useOnboarding'

interface EntryCard { key: string; label: string; meta: string; icon: Component; tone: JumpTone; routeName: string }

const router = useRouter(), session = useSession()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const company = ref<Company>()
const status = ref<WeComIntegrationStatus>()
const loading = ref(true)
const error = ref<ApiProblem>()
const projectTotal = ref<number>()
const memberTotal = ref<number>()
const jobs = ref<ScheduledJob[]>()
const onboardingReady = computed(() => Boolean(company.value && status.value) && !loading.value && !error.value)
const onboarding = usePageOnboarding(() => onboardingReady.value)
watch(onboardingReady, ready => { if (ready) void onboarding.ready() }, { flush: 'post' })

function formatTime(value?: Date | null): string {
  return value ? formatTimestamp(value, timezone.value) : '暂无'
}

const companyItems = computed<DefinitionItem[]>(() => company.value
  ? [
      { key: 'timezone', label: '时区', value: company.value.timezone },
      { key: 'weekStart', label: '周起始日', value: '星期一' },
      { key: 'workday', label: '标准工时', value: `${company.value.defaultWorkdayMinutes} 分钟/日` },
    ]
  : [])

const oauthItems = computed<DefinitionItem[]>(() => status.value
  ? [
      { key: 'corpId', label: 'Corp ID', value: status.value.oauth.corpIdMasked ?? '未配置', mono: Boolean(status.value.oauth.corpIdMasked) },
      { key: 'secret', label: '应用凭据', value: status.value.oauth.appSecretConfigured ? '已安全注入' : '未配置' },
    ]
  : [])

const directoryItems = computed<DefinitionItem[]>(() => status.value
  ? [
      { key: 'corpId', label: 'Corp ID', value: status.value.directory.corpIdMasked ?? '未配置', mono: Boolean(status.value.directory.corpIdMasked) },
      { key: 'secret', label: '目录凭据', value: status.value.directory.directorySecretConfigured ? '已安全注入' : '未配置' },
      { key: 'lastSuccess', label: '最近成功', value: formatTime(status.value.lastSuccessfulRunAt) },
      { key: 'lastProblem', label: '最近异常', value: formatTime(status.value.lastProblemAt) },
    ]
  : [])

const syncMeta = computed(() => {
  const value = status.value
  if (!value) return '通讯录同步记录'
  if (!value.directory.enabled) return '通讯录同步未启用'
  if (value.activeRunId) return '同步进行中'
  return value.lastSuccessfulRunAt ? `最近成功 ${formatRelativeTime(value.lastSuccessfulRunAt)}` : '尚未成功同步'
})

const jobMeta = computed(() => {
  if (!jobs.value) return '定时同步等计划任务'
  const enabled = jobs.value.filter(job => job.enabled).length
  return enabled ? `${enabled} 个任务已启用` : '暂无启用的任务'
})

const entries = computed<EntryCard[]>(() => [
  { key: 'projects', label: '项目', meta: projectTotal.value === undefined ? '全公司项目总览' : `${projectTotal.value} 个进行中项目`, icon: FolderOpened, tone: 'blue', routeName: 'company-projects' },
  { key: 'members', label: '成员与角色', meta: memberTotal.value === undefined ? '账号与平台角色' : `${memberTotal.value} 名在职成员`, icon: User, tone: 'teal', routeName: 'company-members' },
  { key: 'activity', label: '活动日志', meta: '跨项目操作记录与导出', icon: Tickets, tone: 'purple', routeName: 'company-activity' },
  { key: 'sync', label: '同步运行', meta: syncMeta.value, icon: Refresh, tone: 'green', routeName: 'company-sync-runs' },
  { key: 'jobs', label: '定时任务', meta: jobMeta.value, icon: AlarmClock, tone: 'amber', routeName: 'company-scheduled-jobs' },
])

const quiet = () => undefined

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    const [companyResult, statusResult] = await Promise.all([
      identityAdministrationApi.getCompany(),
      identityAdministrationApi.getWeComIntegrationStatus(),
    ])
    company.value = companyResult
    status.value = statusResult
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

// Entry-card counts are decorative; a failure keeps the default meta text instead of blocking the page.
function loadCounts(): void {
  void projectsApi.listProjects({ lifecycle: ProjectLifecycleFilter.Active, page: 0, size: 1 })
    .then(page => { projectTotal.value = page.totalElements }, quiet)
  void identityAdministrationApi.listMembers({ employmentStatus: EmploymentStatus.Active, page: 0, size: 1 })
    .then(page => { memberTotal.value = page.totalElements }, quiet)
  void administrationApi.listScheduledJobs()
    .then(result => { jobs.value = result.items }, quiet)
}

onMounted(() => {
  void load()
  loadCounts()
})
</script>

<template>
  <el-skeleton
    v-if="loading"
    :rows="6"
    animated
  />
  <inline-problem
    v-else-if="error"
    :problem="error"
    title="概览加载失败"
  />
  <div
    v-else
    class="company-overview"
  >
    <yp-surface
      v-if="company"
      class="company-overview__profile"
      data-tour="company-profile"
    >
      <div class="company-identity">
        <span
          class="company-identity__icon"
          aria-hidden="true"
        >
          <el-icon><office-building /></el-icon>
        </span>
        <div>
          <p>公司</p>
          <h2>{{ company.displayName }}</h2>
        </div>
      </div>
      <yp-definition-list
        :columns="3"
        :items="companyItems"
      />
    </yp-surface>

    <nav
      class="company-overview__entries"
      data-tour="company-entries"
      aria-label="公司管理入口"
    >
      <yp-jump-card
        v-for="(entry, index) in entries"
        :key="entry.key"
        :label="entry.label"
        :meta="entry.meta"
        :icon="entry.icon"
        :tone="entry.tone"
        :index="index"
        @open="router.push({ name: entry.routeName })"
      />
    </nav>

    <div
      v-if="status"
      class="company-overview__integrations"
      data-tour="company-integrations"
    >
      <yp-surface title="企微 Web OAuth">
        <template #actions>
          <yp-status-tag
            domain="integration"
            :status="status.oauth.enabled ? 'ENABLED' : 'DISABLED'"
            effect="soft"
          />
          <yp-status-tag
            domain="integration"
            :status="status.oauth.configured ? 'CONFIGURED' : 'INCOMPLETE'"
            effect="soft"
          />
        </template>
        <yp-definition-list :items="oauthItems" />
      </yp-surface>
      <yp-surface title="企微通讯录">
        <template #actions>
          <yp-status-tag
            domain="integration"
            :status="status.directory.enabled ? 'ENABLED' : 'DISABLED'"
            effect="soft"
          />
          <yp-status-tag
            domain="integration"
            :status="status.directory.configured ? 'CONFIGURED' : 'INCOMPLETE'"
            effect="soft"
          />
        </template>
        <yp-definition-list :items="directoryItems" />
      </yp-surface>
    </div>

    <el-alert
      class="security-note"
      type="info"
      :closable="false"
      title="凭据由外部安全配置注入"
      description="此页面和 API 只显示配置状态，不读取、编辑或回显任何 Secret。"
      show-icon
    />
  </div>
</template>

<style scoped>
.company-overview {
  display: grid;
  gap: var(--yp-space-4);
}

.company-overview__entries {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--yp-space-3);
}

.company-identity {
  display: flex;
  align-items: center;
  gap: var(--yp-space-3);
  margin-bottom: var(--yp-space-5);
}

.company-identity__icon {
  display: grid;
  width: 40px;
  height: 40px;
  flex: none;
  place-items: center;
  border-radius: var(--yp-radius-md);
  color: var(--yp-link);
  background: var(--yp-bg-selected);
  font-size: 20px;
}

.company-identity p {
  margin: 0;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
  line-height: var(--yp-type-caption-line);
}

.company-identity h2 {
  margin: 0;
  color: var(--yp-text-primary);
  font: 500 var(--yp-type-section-title-size) / var(--yp-type-section-title-line) var(--yp-font-heading);
}

.company-overview__integrations {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: var(--yp-space-4);
}

@media (max-width: 960px) {
  .company-overview__integrations {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
