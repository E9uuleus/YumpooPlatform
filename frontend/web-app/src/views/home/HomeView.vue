<script setup lang="ts">
import { ElButton, ElCard, ElTag } from 'element-plus'
import { useRouter } from 'vue-router'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpPageHeader from '../../components/yp/YpPageHeader.vue'
import { useSession } from '../../composables/useSession'
import { businessLabel } from '../../design-system/labels'

const router = useRouter()
const session = useSession()
</script>

<template>
  <section class="home-page">
    <yp-page-header
      eyebrow="工作台"
      :title="`欢迎回来，${session.authentication.value?.user.displayName ?? ''}`"
      description="从这里进入当前账号已获授权的 YumpooPlatform 功能。"
    >
      <template #actions>
        <el-button
          v-if="session.isIdentityReader.value"
          type="primary"
          @click="router.push({ name: 'identity-overview' })"
        >
          进入身份管理
        </el-button>
      </template>
    </yp-page-header>

    <div class="home-grid">
      <el-card shadow="never">
        <template #header>
          <strong>当前身份</strong>
        </template>
        <div class="page-stack">
          <yp-assignee
            :user-id="session.authentication.value?.user.id"
            :display-name="session.authentication.value?.user.displayName"
            size="detail"
          />
          <div>
            <span class="muted-text">所属企业</span>
            <p>{{ session.authentication.value?.company.displayName }}</p>
          </div>
          <div>
            <span class="muted-text">当前角色</span>
            <div class="role-list">
              <el-tag
                v-for="role in session.authentication.value?.roles"
                :key="role"
                effect="plain"
              >
                {{ businessLabel(role) }}
              </el-tag>
            </div>
          </div>
        </div>
      </el-card>
      <el-card shadow="never">
        <template #header>
          <strong>可用功能</strong>
        </template>
        <p v-if="session.isIdentityReader.value">
          项目、身份与组织管理。
        </p>
        <p v-else>
          查看当前账号可见的项目。
        </p>
        <div class="action-row">
          <el-button @click="router.push({ name: 'workspace-entry' })">
            打开项目工作台
          </el-button>
        </div>
      </el-card>
    </div>
  </section>
</template>
