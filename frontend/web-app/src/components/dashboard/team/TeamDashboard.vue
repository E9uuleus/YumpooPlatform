<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElAlert, ElButton } from 'element-plus'
import YpSegmented from '../../yp/YpSegmented.vue'
import TeamTimesheet from './TeamTimesheet.vue'
import TeamWorkload from './TeamWorkload.vue'
import { viewOptions, type TeamView } from './teamDashboardModel'
import { useTeamDashboard } from './useTeamDashboard'

const team = useTeamDashboard()
const { optionsError, timezone, period, timesheet, timesheetLoading, timesheetError, workload, workloadLoading, workloadError, tasks } = team
const view = ref<TeamView>('TIMESHEET')
const loading = computed(() => timesheetLoading.value || workloadLoading.value)
defineExpose({ refresh: team.refresh, loading })
</script>

<template>
  <div class="team-dashboard">
    <el-alert
      v-if="optionsError"
      class="team-notice"
      type="error"
      :title="optionsError"
      :closable="false"
    >
      <el-button
        text
        @click="team.refresh()"
      >
        重试
      </el-button>
    </el-alert>
    <TeamTimesheet
      v-show="view === 'TIMESHEET'"
      v-model:period="period"
      :sheet="timesheet"
      :loading="timesheetLoading"
      :error="timesheetError"
      :timezone="timezone"
      @retry="team.loadTimesheet()"
    >
      <template #switch>
        <YpSegmented
          v-model="view"
          :options="viewOptions"
          label="团队视图内容"
        />
      </template>
    </TeamTimesheet>
    <TeamWorkload
      v-show="view === 'WORKLOAD'"
      :workload="workload"
      :loading="workloadLoading"
      :error="workloadError"
      :tasks="tasks"
      @retry="team.loadWorkload()"
      @load="(key, append) => team.loadTasks(key, append)"
    >
      <template #switch>
        <YpSegmented
          v-model="view"
          :options="viewOptions"
          label="团队视图内容"
        />
      </template>
    </TeamWorkload>
  </div>
</template>

<style src="./team.css" />
