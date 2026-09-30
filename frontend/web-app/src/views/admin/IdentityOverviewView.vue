<script setup lang="ts">
import { OfficeBuilding } from '@element-plus/icons-vue'
import type { Company, WeComIntegrationStatus } from '@yumpoo/api-client'
import { ElAlert, ElIcon, ElSkeleton } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { identityAdministrationApi } from '../../api/client'
import { toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpDefinitionList, { type DefinitionItem } from '../../components/yp/YpDefinitionList.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import YpSurface from '../../components/yp/YpSurface.vue'

const company = ref<Company>()
const status = ref<WeComIntegrationStatus>()
const loading = ref(true)
const error = ref<ApiProblem>()

function formatTime(value?: Date | null): string {
  return value ? value.toLocaleString('zh-CN') : '暂无'
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

onMounted(load)
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
    class="identity-overview"
  >
    <yp-surface
      v-if="company"
      class="identity-overview__company"
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

    <div
      v-if="status"
      class="identity-overview__integrations"
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
.identity-overview {
  display: grid;
  gap: var(--yp-space-4);
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

.identity-overview__integrations {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: var(--yp-space-4);
}

@media (max-width: 960px) {
  .identity-overview__integrations {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
