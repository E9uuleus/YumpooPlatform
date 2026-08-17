<script setup lang="ts">
import { ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElTag } from 'element-plus'
import { useRouter } from 'vue-router'
import { useSession } from '../composables/useSession'

const router = useRouter()
const session = useSession()
</script>

<template>
  <section class="home-page">
    <header class="welcome-banner">
      <div>
        <p class="eyebrow">
          工作台
        </p>
        <h2>欢迎回来，{{ session.authentication.value?.user.displayName }}</h2>
        <p>在这里进入已授权的 Yumpoo 平台功能。</p>
      </div>
      <el-button
        v-if="session.isIdentityReader.value"
        type="primary"
        @click="router.push({ name: 'identity-overview' })"
      >
        进入身份管理
      </el-button>
    </header>

    <div class="home-grid">
      <el-card shadow="never">
        <template #header>
          <strong>当前身份</strong>
        </template>
        <el-descriptions
          :column="1"
          border
        >
          <el-descriptions-item label="用户">
            {{ session.authentication.value?.user.displayName }}
          </el-descriptions-item>
          <el-descriptions-item label="公司">
            {{ session.authentication.value?.company.displayName }}
          </el-descriptions-item>
          <el-descriptions-item label="角色">
            <el-tag
              v-for="role in session.authentication.value?.roles"
              :key="role"
              class="role-tag"
              effect="plain"
            >
              {{ role }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
      <el-card shadow="never">
        <template #header>
          <strong>可用功能</strong>
        </template>
        <p v-if="session.isIdentityReader.value">
          你可以查看身份与组织状态、同步运行和成员账号。
        </p>
        <p v-else>
          你的登录状态有效；当前没有额外管理入口。
        </p>
      </el-card>
    </div>
  </section>
</template>
