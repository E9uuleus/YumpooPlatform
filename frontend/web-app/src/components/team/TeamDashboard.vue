<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton } from 'element-plus'
import TeamTimesheet from './TeamTimesheet.vue'
import TeamWorkload from './TeamWorkload.vue'
import { type TeamView } from './teamDashboardModel'
import { useTeamDashboard } from './useTeamDashboard'

const props = defineProps<{ view: TeamView }>()
const team = useTeamDashboard()
const { optionsError, timezone, period, timesheet, timesheetLoading, timesheetError, workload, workloadLoading, workloadError, tasks } = team
const timesheetView = ref<InstanceType<typeof TeamTimesheet>>()
const loading = computed(() => timesheetLoading.value || workloadLoading.value)
const ready = computed(() => Boolean(team.options.value && timesheet.value && workload.value)
  && !loading.value && !optionsError.value && !timesheetError.value && !workloadError.value)
watch(() => props.view, () => timesheetView.value?.closePopovers())
defineExpose({ refresh: team.refresh, loading, ready })
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
      v-show="props.view !== 'WORKLOAD'"
      ref="timesheetView"
      v-model:period="period"
      :sheet="timesheet"
      :loading="timesheetLoading"
      :error="timesheetError"
      :timezone="timezone"
      :overtime="props.view === 'OVERTIME'"
      @retry="team.loadTimesheet()"
    />
    <TeamWorkload
      v-show="props.view === 'WORKLOAD'"
      :workload="workload"
      :loading="workloadLoading"
      :error="workloadError"
      :tasks="tasks"
      @retry="team.loadWorkload()"
      @load="(key, append) => team.loadTasks(key, append)"
    />
  </div>
</template>

<style src="./team.css" />
