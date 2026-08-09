<script setup lang="ts">
import { ElAlert, ElCard, ElTag } from 'element-plus'
</script>

<template>
  <el-card
    class="skeleton-card"
    shadow="never"
  >
    <template #header>
      <div class="card-heading">
        <span>Vue SPA 已就绪</span>
        <el-tag size="small">
          M0-07B
        </el-tag>
      </div>
    </template>
    <p>
      当前仅提供可构建、可测试的在线应用壳。业务页面、身份接入、数据访问与 API
      契约将在后续里程碑实现。
    </p>
    <el-alert
      title="模块边界已启用"
      description="Web 运行时不会直接访问 Node、Electron 或桌面实现。"
      type="info"
      :closable="false"
      show-icon
    />
  </el-card>
</template>
