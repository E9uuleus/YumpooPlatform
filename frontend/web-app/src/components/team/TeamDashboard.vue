<script setup lang="ts">
import YpNotice from '../yp/YpNotice.vue'
import { computed, ref, watch } from 'vue'
import { ElButton } from 'element-plus'
import TeamTimesheet from './TeamTimesheet.vue'
import TeamProjectHours from './TeamProjectHours.vue'
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
    <yp-notice
      v-if="optionsError"
      class="team-notice"
      tone="danger"
    >
      {{ optionsError }}

      <template #actions>
        <el-button
          text
          @click="team.refresh()"
        >
          重试
        </el-button>
      </template>
    </yp-notice>
    <TeamTimesheet
      v-show="props.view === 'TIMESHEET' || props.view === 'OVERTIME'"
      ref="timesheetView"
      v-model:period="period"
      :sheet="timesheet"
      :loading="timesheetLoading"
      :error="timesheetError"
      :timezone="timezone"
      :overtime="props.view === 'OVERTIME'"
      @retry="team.loadTimesheet()"
    />
    <TeamProjectHours
      v-if="props.view === 'PROJECTS'"
      v-model:period="period"
      :sheet="timesheet"
      :loading="timesheetLoading"
      :error="timesheetError"
      :timezone="timezone"
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
