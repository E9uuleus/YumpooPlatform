<script setup lang="ts">
import type { ProjectDetail } from '@yumpoo/api-client'
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { toApiProblem, type ApiProblem } from '../../api/problems'
import ActivityTimeline from '../../components/collaboration/ActivityTimeline.vue'
import InlineProblem from '../../components/InlineProblem.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'

const route = useRoute()
const projectId = String(route.params.projectId)
const project = ref<ProjectDetail>()
const error = ref<ApiProblem>()

async function loadProject(): Promise<void> {
  error.value = undefined
  try { project.value = await projectsApi.getProject({ projectId }) }
  catch (reason) { error.value = await toApiProblem(reason) }
}

onMounted(() => void loadProject())
</script>

<template>
  <section class="project-activity-view">
    <project-workspace-header section="activity" :project="project" title="项目动态" />
    <inline-problem v-if="error" :problem="error" />
    <div v-else class="project-activity-view__panel">
      <activity-timeline :project-id="projectId" />
    </div>
  </section>
</template>

<style scoped>
.project-activity-view { display: grid; gap: var(--yp-space-5); }
.project-activity-view__panel { padding: var(--yp-space-5); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
</style>
