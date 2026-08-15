<script setup lang="ts">
import { ElButton, ElResult } from 'element-plus'
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-result
    icon="info"
    title="页面不存在"
    sub-title="请检查地址，或返回 Yumpoo 首页。"
  >
    <template #extra>
      <el-button
        type="primary"
        @click="router.push({ name: 'home' })"
      >
        返回首页
      </el-button>
    </template>
  </el-result>
</template>
