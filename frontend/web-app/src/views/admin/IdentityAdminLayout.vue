<script setup lang="ts">
import { ElButton } from 'element-plus'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useIdentityAdmin } from '../../composables/useIdentityAdmin'

const route = useRoute()
const router = useRouter()
const {
  authentication,
} = useIdentityAdmin()

const activeTab = computed(() => route.name?.toString() ?? 'identity-overview')

function navigate(name: string): void {
  void router.push({ name })
}

</script>

<template>
  <section class="identity-admin">
    <header class="page-title">
      <div>
        <p class="eyebrow">
          身份与组织
        </p>
        <h2>身份管理</h2>
        <p>查看公司与企微状态、诊断通讯录同步，并管理成员账号。</p>
      </div>
      <span
        v-if="authentication"
        class="actor-label"
      >
        {{ authentication.user.displayName }}
      </span>
    </header>

    <nav
      class="identity-tabs"
      aria-label="身份管理功能"
    >
      <el-button
        :type="activeTab === 'identity-overview' ? 'primary' : 'default'"
        @click="navigate('identity-overview')"
      >
        概览
      </el-button>
      <el-button
        :type="activeTab === 'identity-sync-runs' ? 'primary' : 'default'"
        @click="navigate('identity-sync-runs')"
      >
        同步运行
      </el-button>
      <el-button
        :type="activeTab === 'identity-members' ? 'primary' : 'default'"
        @click="navigate('identity-members')"
      >
        成员管理
      </el-button>
    </nav>
    <router-view />
  </section>
</template>
