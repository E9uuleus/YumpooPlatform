<script setup lang="ts">
import { ProjectLifecycle, type ProjectDetail } from '@yumpoo/api-client'
import { ElButton } from 'element-plus'
import { computed } from 'vue'
import type { ApiProblem } from '../../api/problems'
import { useProjectLifecycleActions } from './useProjectLifecycleActions'

const props = defineProps<{ project: ProjectDetail }>()
const emit = defineEmits<{ changed: []; problem: [problem: ApiProblem] }>()
const { busy, toggleEnded, archive, restore } = useProjectLifecycleActions(() => props.project, {
  changed: () => emit('changed'),
  problem: problem => emit('problem', problem),
})
const canOperate = computed(() => {
  const capabilities = props.project.capabilities
  return capabilities.canArchive || capabilities.canRestore || capabilities.canEnd || capabilities.canResume
})
</script>

<template>
  <div
    v-if="canOperate"
    class="project-lifecycle"
  >
    <section
      v-if="project.capabilities.canEnd || project.capabilities.canResume"
      class="project-lifecycle__row"
      aria-labelledby="lifecycle-status-title"
    >
      <div>
        <h2 id="lifecycle-status-title">
          项目状态
        </h2>
        <p v-if="project.endedAt">
          项目已结束，内容只读，仍对成员可见并计入统计。
        </p>
        <p v-else>
          项目完成后可以结束：内容变为只读，仍对成员可见并计入统计。
        </p>
      </div>
      <el-button
        :type="project.endedAt ? 'primary' : undefined"
        :loading="busy"
        @click="toggleEnded"
      >
        {{ project.endedAt ? '重新打开项目' : '结束项目' }}
      </el-button>
    </section>
    <section
      v-if="project.capabilities.canArchive || project.capabilities.canRestore"
      class="project-lifecycle__row"
      aria-labelledby="lifecycle-actions-title"
    >
      <div>
        <h2 id="lifecycle-actions-title">
          危险区域
        </h2>
        <p>归档项目仅负责人和企业管理员可只读浏览或恢复，不再计入统计。</p>
      </div>
      <el-button
        v-if="project.capabilities.canArchive && project.lifecycle === ProjectLifecycle.Active"
        type="danger"
        :loading="busy"
        @click="archive"
      >
        归档项目
      </el-button>
      <el-button
        v-if="project.capabilities.canRestore"
        type="primary"
        :loading="busy"
        @click="restore"
      >
        恢复项目
      </el-button>
    </section>
  </div>
</template>

<style scoped>
.project-lifecycle { display: grid; gap: var(--yp-space-6); }
.project-lifecycle__row { display: flex; align-items: center; justify-content: space-between; gap: var(--yp-space-4); }
.project-lifecycle__row h2,
.project-lifecycle__row p { margin: 0; }
.project-lifecycle__row h2 { font-size: var(--yp-type-card-title-size); font-weight: 600; }
.project-lifecycle__row p { margin-top: var(--yp-space-1); color: var(--yp-text-secondary); }
@media (max-width: 720px) { .project-lifecycle__row { flex-direction: column; align-items: flex-start; } }
</style>
