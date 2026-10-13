<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { ElButton, ElTooltip } from 'element-plus'
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import TeamDashboard from '../components/team/TeamDashboard.vue'

const VIEWS = {
  timesheet: { view: 'TIMESHEET', title: '成员工时' },
  overtime: { view: 'OVERTIME', title: '加班统计' },
  workload: { view: 'WORKLOAD', title: '成员当前任务' },
} as const

const route = useRoute()
const current = computed(() => VIEWS[route.params.view as keyof typeof VIEWS])
const team = ref<InstanceType<typeof TeamDashboard>>()
</script>

<template>
  <section class="dashboard-page">
    <header class="dashboard-header">
      <h1 class="review-page-title">
        {{ current.title }}
      </h1>
      <div class="dashboard-header-actions">
        <el-tooltip content="刷新数据">
          <el-button
            :icon="Refresh"
            aria-label="刷新团队视图"
            :loading="!!team?.loading"
            text
            @click="team?.refresh()"
          />
        </el-tooltip>
      </div>
    </header>
    <TeamDashboard
      ref="team"
      :view="current.view"
    />
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
