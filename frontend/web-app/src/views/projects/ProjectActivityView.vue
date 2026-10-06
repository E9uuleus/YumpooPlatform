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
  <div class="project-view-stack">
    <project-workspace-header
      section="activity"
      :project="project"
      title="项目动态"
    />
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <section
      v-else
      class="project-activity"
      aria-labelledby="project-activity-title"
    >
      <header class="project-page-heading">
        <div>
          <h2 id="project-activity-title">
            动态
          </h2>
          <p>按时间查看工作项、讨论、成员与计时的变化。</p>
        </div>
      </header>
      <activity-timeline :project-id="projectId" />
    </section>
  </div>
</template>

<style scoped>
.project-activity {
  display: grid;
  gap: var(--yp-space-4);
}
</style>
