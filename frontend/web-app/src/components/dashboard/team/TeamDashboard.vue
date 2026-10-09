<script setup lang="ts">
import { computed, type DefineComponent } from 'vue'
import { ElAlert, ElButton, ElOption as ElOptionRaw, ElSelect as ElSelectRaw } from 'element-plus'
import TeamTimesheet from './TeamTimesheet.vue'
import TeamWorkload from './TeamWorkload.vue'
import { useTeamDashboard } from './useTeamDashboard'

const ElSelect = ElSelectRaw as unknown as DefineComponent
const ElOption = ElOptionRaw as unknown as DefineComponent
const team = useTeamDashboard()
const { options, optionsError, timezone, projectIds, userIds, period, timesheet, timesheetLoading, timesheetError, workload, workloadLoading, workloadError, tasks } = team
const scopeLabel = computed(() => projectIds.value.length
  ? options.value?.projects.filter(p => projectIds.value.includes(p.id)).map(p => p.name).join('、') || `${projectIds.value.length} 个项目`
  : '全部项目')
const memberScopeLabel = computed(() => userIds.value.length
  ? options.value?.members.filter(m => userIds.value.includes(m.userId)).map(m => m.displayName).join('、') || `${userIds.value.length} 位成员`
  : '全部成员')
const loading = computed(() => timesheetLoading.value || workloadLoading.value)
defineExpose({ refresh: team.refresh, loading })
</script>

<template>
  <div class="team-dashboard">
    <div class="dashboard-toolbar team-toolbar">
      <el-select
        v-model="projectIds"
        multiple
        collapse-tags
        collapse-tags-tooltip
        filterable
        clearable
        placeholder="全部项目"
        aria-label="按项目筛选"
        class="team-filter"
      >
        <el-option
          v-for="project in options?.projects ?? []"
          :key="project.id"
          :value="project.id"
          :label="project.lifecycle === 'ARCHIVED' ? `${project.name}（已归档）` : project.name"
        />
      </el-select>
      <el-select
        v-model="userIds"
        multiple
        collapse-tags
        collapse-tags-tooltip
        filterable
        clearable
        placeholder="全部成员"
        aria-label="按成员筛选"
        class="team-filter"
      >
        <el-option
          v-for="member in options?.members ?? []"
          :key="member.userId"
          :value="member.userId"
          :label="member.displayName"
        />
      </el-select>
      <span class="team-toolbar__hint">全公司范围 · 仅公司管理员可见</span>
    </div>
    <main class="dashboard-canvas team-canvas">
      <el-alert
        v-if="optionsError"
        class="dashboard-notice"
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
        v-model:period="period"
        :sheet="timesheet"
        :loading="timesheetLoading"
        :error="timesheetError"
        :timezone="timezone"
        :scope-label="scopeLabel"
        :member-scope-label="memberScopeLabel"
        @retry="team.loadTimesheet()"
      />
      <TeamWorkload
        :workload="workload"
        :loading="workloadLoading"
        :error="workloadError"
        :tasks="tasks"
        @retry="team.loadWorkload()"
        @load="(key, append) => team.loadTasks(key, append)"
      />
    </main>
  </div>
</template>

<style src="./team.css" />
