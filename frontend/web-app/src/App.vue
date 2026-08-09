<script setup lang="ts">
import { ElContainer, ElHeader, ElMain, ElTag } from 'element-plus'
import { computed } from 'vue'

const clientLabel = computed(() =>
  window.yumpooDesktop?.client === 'electron' ? 'Electron 在线壳' : 'Web 浏览器',
)
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div>
        <p class="app-kicker">
          YUMPOO PLATFORM
        </p>
        <h1>一期工程骨架</h1>
      </div>
      <el-tag
        effect="plain"
        type="success"
      >
        {{ clientLabel }}
      </el-tag>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>
