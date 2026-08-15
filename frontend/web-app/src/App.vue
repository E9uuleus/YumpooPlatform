<script setup lang="ts">
import { ElButton, ElContainer, ElHeader, ElMain, ElTag } from 'element-plus'
import { computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { useIdentityAdmin } from './composables/useIdentityAdmin'

const clientLabel = computed(() =>
  window.yumpooDesktop?.client === 'electron' ? 'Electron 在线壳' : 'Web 浏览器',
)
const { isReader, loadAuthentication } = useIdentityAdmin()

onMounted(loadAuthentication)
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div>
        <p class="app-kicker">YUMPOO PLATFORM</p>
        <h1>一期工程骨架</h1>
      </div>
      <div class="header-actions">
        <router-link v-if="isReader" to="/admin/identity/overview">
          <el-button plain>身份管理</el-button>
        </router-link>
        <el-tag effect="plain" type="success">{{ clientLabel }}</el-tag>
      </div>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>
