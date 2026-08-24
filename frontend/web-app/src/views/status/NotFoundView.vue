<script setup lang="ts">
import { ElButton, ElResult } from 'element-plus'
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <main class="result-page">
    <el-result
      icon="info"
      title="页面不存在"
      sub-title="请检查地址，或返回 YumpooPlatform 工作台。"
    >
      <template #extra>
        <el-button
          type="primary"
          @click="router.push({ name: 'workspace-entry' })"
        >
          返回工作台
        </el-button>
      </template>
    </el-result>
  </main>
</template>
