<script setup lang="ts">
import { computed, onMounted, ref, type DefineComponent } from 'vue'
import { ProjectLifecycleFilter, type ProjectSummary } from '@yumpoo/api-client'
import type { RailProject } from '@yumpoo/preload-contract'
import { ElButton, ElIcon, ElInput, ElOption as ElOptionRaw, ElSelect as ElSelectRaw } from 'element-plus'
import { Close, Rank } from '@element-plus/icons-vue'
import { projectsApi } from '../../api/client'
import { MY_PROJECT_ACCESSES } from '../projects/projectScope'
import { projectColors } from '../team/teamDashboardModel'
import { railLabel } from '../desktop/quickTime'
import { sameRailProjects } from '../desktop/railProjects'

const ElSelect = ElSelectRaw as unknown as DefineComponent
const ElOption = ElOptionRaw as unknown as DefineComponent
const MAX_PROJECTS = 6
const props = defineProps<{ items: RailProject[]; defaults: RailProject[] }>()
const emit = defineEmits<{ update: [items: RailProject[]] }>()
const available = ref<ProjectSummary[]>([])
const dragging = ref<number | null>(null)
const tones = computed(() => projectColors(props.items.map(item => item.id)))
const options = computed(() => available.value.filter(project => !props.items.some(item => item.id === project.id)))

onMounted(async () => {
  try {
    available.value = (await projectsApi.listProjects({ lifecycle: ProjectLifecycleFilter.Active, ended: false, actorAccesses: MY_PROJECT_ACCESSES, page: 0, size: 100 })).items
  } catch { available.value = [] }
})

function add(id: string) {
  const project = available.value.find(item => item.id === id)
  if (project) emit('update', [...props.items, { id: project.id, name: project.name.slice(0, 64) }])
}
function rename(id: string, value: string) {
  const label = [...value.trim()].slice(0, 3).join('')
  emit('update', props.items.map(item => item.id !== id ? item : label ? { ...item, label } : { id: item.id, name: item.name }))
}
function drop(target: number) {
  const from = dragging.value
  dragging.value = null
  if (from === null || from === target) return
  const items = [...props.items]
  const [moved] = items.splice(from, 1)
  items.splice(target, 0, moved!)
  emit('update', items)
}
</script>

<template>
  <div
    id="settings-rail-projects"
    class="settings-section"
  >
    项目<span class="settings-section__aside">拖动排序 · 最多 {{ MAX_PROJECTS }} 个 · 可填 1–3 字简称</span>
    <el-button
      size="small"
      :disabled="sameRailProjects(items, defaults)"
      :title="sameRailProjects(items, defaults) ? '已是默认项目' : undefined"
      @click="emit('update', defaults)"
    >
      恢复默认
    </el-button>
  </div>
  <div class="rail-projects">
    <div
      v-for="(item, index) in items"
      :key="item.id"
      class="rail-projects__row"
      :class="{ 'is-dragging': dragging === index }"
      draggable="true"
      @dragstart="dragging = index"
      @dragover.prevent
      @drop.prevent="drop(index)"
      @dragend="dragging = null"
    >
      <el-icon class="rail-projects__grip">
        <Rank />
      </el-icon>
      <span
        class="rail-projects__badge"
        :style="{ background: tones.get(item.id) }"
      >{{ railLabel(item) }}</span>
      <span class="rail-projects__name">{{ item.name }}</span>
      <el-input
        class="rail-projects__label"
        size="small"
        :model-value="item.label ?? ''"
        :model-modifiers="{ lazy: true }"
        :maxlength="3"
        placeholder="简称"
        :aria-label="`${item.name} 的侧边栏简称`"
        @change="(value: string) => rename(item.id, value)"
      />
      <button
        type="button"
        class="rail-projects__remove"
        :aria-label="`移除 ${item.name}`"
        @click="emit('update', items.filter(other => other.id !== item.id))"
      >
        <el-icon><Close /></el-icon>
      </button>
    </div>
    <el-select
      v-if="items.length < MAX_PROJECTS"
      class="rail-projects__add"
      :model-value="''"
      filterable
      size="small"
      placeholder="添加项目"
      no-data-text="没有可添加的项目"
      @update:model-value="add"
    >
      <el-option
        v-for="project in options"
        :key="project.id"
        :label="project.name"
        :value="project.id"
      />
    </el-select>
  </div>
</template>

<style scoped>
.rail-projects{display:flex;flex-direction:column;gap:4px;padding:6px 16px 12px}
.rail-projects__row{display:flex;align-items:center;gap:10px;height:36px;padding:0 8px;border-radius:8px;background:var(--yp-bg-sunken);cursor:grab;transition:opacity .15s}
.rail-projects__row.is-dragging{opacity:.35}
.rail-projects__grip{color:var(--yp-text-muted)}
.rail-projects__badge{display:grid;place-items:center;min-width:22px;height:22px;padding:0 4px;box-sizing:border-box;border-radius:7px;color:var(--yp-on-solid);font-size:11.5px;font-weight:500;white-space:nowrap}
.rail-projects__label{flex:none;width:64px}
.rail-projects__name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.rail-projects__remove{display:grid;place-items:center;padding:0 4px;border:0;background:none;color:var(--yp-text-muted);cursor:pointer}
.rail-projects__remove:hover{color:var(--yp-text-primary)}
.rail-projects__add{align-self:flex-start;width:180px;margin-top:4px}
</style>
