<script setup lang="ts">
import type { Company, WeComIntegrationStatus } from '@yumpoo/api-client'
import {
  ElAlert,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElSkeleton,
} from 'element-plus'
import { onMounted, ref } from 'vue'
import { identityAdministrationApi } from '../../api/client'
import { toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'

const company = ref<Company>()
const status = ref<WeComIntegrationStatus>()
const loading = ref(true)
const error = ref<ApiProblem>()

function formatTime(value?: Date | null): string {
  return value ? value.toLocaleString('zh-CN') : '暂无'
}

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
    class="overview-grid"
  >
    <el-card shadow="never">
      <template #header>
        <strong>公司</strong>
      </template>
      <el-descriptions
        v-if="company"
        :column="1"
        border
      >
        <el-descriptions-item label="名称">
          {{ company.displayName }}
        </el-descriptions-item>
        <el-descriptions-item label="时区">
          {{ company.timezone }}
        </el-descriptions-item>
        <el-descriptions-item label="周起始日">
          星期一
        </el-descriptions-item>
        <el-descriptions-item label="标准工时">
          {{ company.defaultWorkdayMinutes }} 分钟/日
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card
      v-if="status"
      shadow="never"
    >
      <template #header>
        <strong>企微 Web OAuth</strong>
      </template>
      <el-descriptions
        :column="1"
        border
      >
        <el-descriptions-item label="运行开关">
          <yp-status-tag
            domain="integration"
            :status="status.oauth.enabled ? 'ENABLED' : 'DISABLED'"
            effect="soft"
          />
        </el-descriptions-item>
        <el-descriptions-item label="配置状态">
          <yp-status-tag
            domain="integration"
            :status="status.oauth.configured ? 'CONFIGURED' : 'INCOMPLETE'"
            effect="soft"
          />
        </el-descriptions-item>
        <el-descriptions-item label="Corp ID">
          {{ status.oauth.corpIdMasked ?? '未配置' }}
        </el-descriptions-item>
        <el-descriptions-item label="应用凭据">
          {{ status.oauth.appSecretConfigured ? '已安全注入' : '未配置' }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card
      v-if="status"
      shadow="never"
    >
      <template #header>
        <strong>企微通讯录</strong>
      </template>
      <el-descriptions
        :column="1"
        border
      >
        <el-descriptions-item label="运行开关">
          <yp-status-tag
            domain="integration"
            :status="status.directory.enabled ? 'ENABLED' : 'DISABLED'"
            effect="soft"
          />
        </el-descriptions-item>
        <el-descriptions-item label="配置状态">
          <yp-status-tag
            domain="integration"
            :status="status.directory.configured ? 'CONFIGURED' : 'INCOMPLETE'"
            effect="soft"
          />
        </el-descriptions-item>
        <el-descriptions-item label="Corp ID">
          {{ status.directory.corpIdMasked ?? '未配置' }}
        </el-descriptions-item>
        <el-descriptions-item label="目录凭据">
          {{ status.directory.directorySecretConfigured ? '已安全注入' : '未配置' }}
        </el-descriptions-item>
        <el-descriptions-item label="最近成功">
          {{ formatTime(status.lastSuccessfulRunAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="最近异常">
          {{ formatTime(status.lastProblemAt) }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

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
