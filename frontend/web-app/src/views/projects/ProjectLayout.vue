<script setup lang="ts">
import { ProjectLifecycle, type ProjectDetail } from '@yumpoo/api-client'
import { ElAlert } from 'element-plus'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { PROJECT_LIFECYCLE_CHANGED } from '../../composables/projectLifecycleEvents'

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
watch(() => route.params.projectId, () => { project.value = undefined; void load() }, { immediate: true })
onMounted(() => window.addEventListener(PROJECT_LIFECYCLE_CHANGED, lifecycleChanged))
onBeforeUnmount(() => { request++; window.removeEventListener(PROJECT_LIFECYCLE_CHANGED, lifecycleChanged) })
</script>

<template>
  <section class="project-workspace">
    <el-alert
      v-if="project?.lifecycle === ProjectLifecycle.Archived"
      class="archived-project-banner"
      type="info"
      :closable="false"
      show-icon
      title="项目已归档，当前为只读浏览。仅负责人和企业管理员可访问或恢复，归档项目不计入统计。"
    />
    <router-view />
  </section>
</template>

<style scoped>
.archived-project-banner { margin-bottom: var(--yp-space-4); }
</style>
