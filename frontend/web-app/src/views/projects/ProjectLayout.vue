<script setup lang="ts">
import { ProjectLifecycle, type ProjectDetail } from '@yumpoo/api-client'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { usePageLoad } from '../../brand/page-progress'
import { projectStatus } from '../../components/projects/projectStatus'
import YpNotice from '../../components/yp/YpNotice.vue'
import { PROJECT_LIFECYCLE_CHANGED } from '../../composables/projectLifecycleEvents'

const pageLoad = usePageLoad()
const route = useRoute()
const project = ref<ProjectDetail>()
let request = 0
async function load(): Promise<void> {
  const projectId = String(route.params.projectId), sequence = ++request
  try {
    const next = await projectsApi.getProject({ projectId })
    if (sequence === request) project.value = next
  } catch { if (sequence === request) project.value = undefined }
}
function lifecycleChanged(event: Event): void {
  if ((event as CustomEvent<{ projectId: string }>).detail.projectId === route.params.projectId) void load()
}
watch(() => route.params.projectId, () => { project.value = undefined; void pageLoad.track(load) }, { immediate: true })
onMounted(() => window.addEventListener(PROJECT_LIFECYCLE_CHANGED, lifecycleChanged))
onBeforeUnmount(() => { request++; window.removeEventListener(PROJECT_LIFECYCLE_CHANGED, lifecycleChanged) })
</script>

<template>
  <section
    class="project-workspace"
    :class="{ 'project-workspace--archived': project && projectStatus(project) !== 'ACTIVE' }"
  >
    <yp-notice
      v-if="project?.lifecycle === ProjectLifecycle.Archived"
      class="archived-project-banner"
      tone="danger"
    >
      项目已归档，当前为只读浏览。仅负责人和企业管理员可访问或恢复，归档项目不计入统计。
    </yp-notice>
    <yp-notice
      v-else-if="project?.endedAt"
      class="ended-project-banner"
      tone="info"
    >
      项目已结束，内容为只读，仍计入统计。负责人或公司管理员可以在「设置」中重新打开。
    </yp-notice>
    <router-view :key="route.name === 'project-statistics' ? String(route.params.projectId) : 'project-page'" />
  </section>
</template>

<style scoped>
.project-workspace--archived {
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: max-content minmax(0, 1fr);
  gap: var(--yp-space-2);
}

.project-workspace--archived:has(> .project-overview-stack) {
  height: 100%;
  min-height: 0;
}

.archived-project-banner {
  align-self: start;
  min-height: 36px;
}
</style>
