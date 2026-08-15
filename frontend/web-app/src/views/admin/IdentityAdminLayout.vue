<script setup lang="ts">
import { ElAlert, ElButton, ElSkeleton } from 'element-plus'
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useIdentityAdmin } from '../../composables/useIdentityAdmin'

const route = useRoute()
const router = useRouter()
const {
  authentication,
  authenticationError,
  authenticationLoading,
  isReader,
  loadAuthentication,
} = useIdentityAdmin()

const activeTab = computed(() => route.name?.toString() ?? 'identity-overview')

function navigate(name: string): void {
  void router.push({ name })
}

onMounted(loadAuthentication)
</script>

<template>
  <section class="identity-admin">
    <header class="page-title">
      <div>
        <p class="eyebrow">身份与组织</p>
        <h2>身份管理</h2>
        <p>查看公司与企微状态、诊断通讯录同步，并管理成员账号。</p>
      </div>
      <span v-if="authentication" class="actor-label">
        {{ authentication.user.displayName }}
      </span>
    </header>

    <el-skeleton v-if="authenticationLoading" :rows="5" animated />
    <el-alert
      v-else-if="authenticationError"
      type="error"
      :closable="false"
      title="无法确认管理权限"
      :description="authenticationError.message"
      show-icon
    />
    <el-alert
      v-else-if="!isReader"
      type="warning"
      :closable="false"
      title="无权访问身份管理"
      description="此功能区仅供 APP_MANAGER 或 COMPANY_ADMIN 使用。"
      show-icon
    />
    <template v-else>
      <nav class="identity-tabs" aria-label="身份管理功能">
        <el-button :type="activeTab === 'identity-overview' ? 'primary' : 'default'" @click="navigate('identity-overview')">概览</el-button>
        <el-button :type="activeTab === 'identity-sync-runs' ? 'primary' : 'default'" @click="navigate('identity-sync-runs')">同步运行</el-button>
        <el-button :type="activeTab === 'identity-members' ? 'primary' : 'default'" @click="navigate('identity-members')">成员管理</el-button>
      </nav>
      <router-view />
    </template>
  </section>
</template>
