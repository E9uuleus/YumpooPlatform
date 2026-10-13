<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { ElButton, ElTooltip } from 'element-plus'
import { ref } from 'vue'
import MyTimeView from '../components/my-time/MyTimeView.vue'

const myTime = ref<InstanceType<typeof MyTimeView>>()
</script>

<template>
  <section class="dashboard-page">
    <header class="dashboard-header">
      <h1 class="review-page-title">
        我的工时
      </h1>
      <div class="dashboard-header-actions">
        <el-tooltip content="刷新数据">
          <el-button
            :icon="Refresh"
            aria-label="刷新我的工时"
            :loading="!!myTime?.loading"
            text
            @click="myTime?.refresh()"
          />
        </el-tooltip>
      </div>
    </header>
    <MyTimeView ref="myTime" />
  </section>
</template>

<style src="../components/dashboard/dashboard.css" />
<style scoped>
.review-page-title {
  align-self: center;
  margin: 0;
  font-size: var(--yp-type-card-title-size);
  font-weight: 600;
}
</style>
