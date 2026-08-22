<script setup lang="ts">
import { ElButton, ElResult } from 'element-plus'
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <main class="result-page">
    <el-result
      icon="warning"
      title="无权访问此功能"
      sub-title="当前账号没有进入该管理区域所需的角色。"
    >
      <template #extra>
        <el-button
          type="primary"
          @click="router.push({ name: 'home' })"
        >
          返回工作台
        </el-button>
      </template>
    </el-result>
  </main>
</template>
