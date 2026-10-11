<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElButton, ElIcon, ElInput, ElPopover, ElTooltip } from 'element-plus'
import { Calendar, Check, DataAnalysis, Menu, Plus, Search, User } from '@element-plus/icons-vue'
import type { DashboardSummary } from '@yumpoo/api-client'
import YpEmptyState from '../yp/YpEmptyState.vue'
import { MY_TIME_VIEW_ID, TEAM_VIEW_ID } from './dashboardModel'

interface Tab { id: string; name: string; fixed?: 'mine' | 'team' }

const props = defineProps<{ dashboards: DashboardSummary[]; activeId: string; activeName: string; team: boolean; renamable: boolean }>()
const emit = defineEmits<{ select: [id: string]; create: []; rename: [name: string] }>()
const strip = ref<HTMLElement>()
const listOpen = ref(false), search = ref(''), editing = ref(false), draft = ref('')
const tabs = computed<Tab[]>(() => [
  { id: MY_TIME_VIEW_ID, name: '我的工时', fixed: 'mine' },
  ...(props.team ? [{ id: TEAM_VIEW_ID, name: '团队视图', fixed: 'team' as const }] : []),
  ...props.dashboards.map(d => ({ id: d.id, name: d.id === props.activeId ? props.activeName : d.name })),
])
const choices = computed(() => tabs.value.filter(tab => tab.name.toLowerCase().includes(search.value.trim().toLowerCase())))

function select(id: string) { listOpen.value = false; if (id !== props.activeId) emit('select', id) }
function create() { listOpen.value = false; emit('create') }
async function startRename(tab: Tab) {
  if (tab.fixed || tab.id !== props.activeId || !props.renamable) return
  draft.value = tab.name; editing.value = true
  await nextTick()
  const input = strip.value?.querySelector<HTMLInputElement>('.dashboard-tab-rename input')
  input?.focus(); input?.select()
}
function commitRename() {
  if (!editing.value) return
  editing.value = false
  const value = draft.value.trim()
  if (value && value.length <= 100 && value !== props.activeName) emit('rename', value)
}
function scrollStrip(event: WheelEvent) {
  if (!strip.value || strip.value.scrollWidth <= strip.value.clientWidth || Math.abs(event.deltaX) > Math.abs(event.deltaY)) return
  event.preventDefault(); strip.value.scrollLeft += event.deltaY
}
function move(event: KeyboardEvent, index: number) {
  const last = tabs.value.length - 1
  const target = { ArrowRight: index === last ? 0 : index + 1, ArrowLeft: index === 0 ? last : index - 1, Home: 0, End: last }[event.key]
  if (target === undefined) return
  event.preventDefault()
  strip.value?.querySelectorAll<HTMLElement>('.dashboard-tab')[target]?.focus()
}
watch(() => [props.activeId, tabs.value.length], async () => {
  editing.value = false
  await nextTick()
  strip.value?.querySelector('.dashboard-tab.active')?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}, { immediate: true })
</script>

<template>
  <div class="dashboard-tabs">
    <div
      ref="strip"
      class="dashboard-tabs__strip"
      role="tablist"
      aria-label="仪表板"
      @wheel="scrollStrip"
    >
      <template
        v-for="(tab, index) in tabs"
        :key="tab.id"
      >
        <el-input
          v-if="editing && tab.id === activeId"
          v-model="draft"
          class="dashboard-tab-rename"
          maxlength="100"
          aria-label="仪表板名称"
          @keydown.enter.prevent="commitRename"
          @keydown.esc.prevent="editing = false"
          @blur="commitRename"
        />
        <button
          v-else
          class="dashboard-tab"
          :class="{ active: tab.id === activeId, 'dashboard-tab--fixed': tab.fixed }"
          type="button"
          role="tab"
          :aria-selected="tab.id === activeId"
          :tabindex="tab.id === activeId ? 0 : -1"
          :title="tab.fixed === 'mine' ? '本人每日工时（按公司时区）' : tab.fixed ? '成员工时与当前任务（仅公司管理员可见）' : tab.name"
          @click="select(tab.id)"
          @dblclick="startRename(tab)"
          @keydown="move($event, index)"
        >
          <el-icon v-if="tab.fixed">
            <Calendar v-if="tab.fixed === 'mine'" /><User v-else />
          </el-icon><span>{{ tab.name }}</span>
        </button>
        <span
          v-if="tab.fixed && tabs[index + 1] && !tabs[index + 1]!.fixed"
          class="dashboard-tabs__divider"
          aria-hidden="true"
        />
      </template>
    </div>
    <el-popover
      v-model:visible="listOpen"
      placement="bottom-end"
      :width="320"
      trigger="click"
      @show="search = ''"
    >
      <template #reference>
        <span class="dashboard-tabs__action">
          <el-tooltip content="全部仪表板">
            <el-button
              :icon="Menu"
              text
              aria-label="全部仪表板"
            />
          </el-tooltip>
        </span>
      </template>
      <el-input
        v-model="search"
        :prefix-icon="Search"
        placeholder="查找仪表板"
        clearable
      />
      <div class="dashboard-switch-list">
        <button
          v-for="tab in choices"
          :key="tab.id"
          :class="{ active: tab.id === activeId }"
          @click="select(tab.id)"
        >
          <el-icon>
            <Calendar v-if="tab.fixed === 'mine'" /><User v-else-if="tab.fixed" /><DataAnalysis v-else />
          </el-icon><span>{{ tab.name }}</span><el-icon v-if="tab.id === activeId">
            <Check />
          </el-icon>
        </button><yp-empty-state
          v-if="!choices.length"
          title="暂无仪表板"
          description=""
          compact
        />
      </div>
      <el-button
        text
        type="primary"
        :icon="Plus"
        @click="create"
      >
        新建仪表板
      </el-button>
    </el-popover>
    <el-tooltip content="新建仪表板">
      <el-button
        class="dashboard-tabs__action"
        :icon="Plus"
        text
        aria-label="新建仪表板"
        @click="create"
      />
    </el-tooltip>
  </div>
</template>

<style scoped>
.dashboard-tabs{display:flex;align-items:stretch;gap:4px;min-width:0;flex:1}
.dashboard-tabs__strip{display:flex;align-items:stretch;min-width:0;overflow-x:auto;overflow-y:hidden;scrollbar-width:none}
.dashboard-tabs__strip::-webkit-scrollbar{display:none}
.dashboard-tab{position:relative;display:inline-flex;align-items:center;gap:6px;flex:none;max-width:240px;padding:0 14px;border:0;border-radius:6px 6px 0 0;background:none;color:var(--yp-text-secondary);font:500 15px/22px var(--yp-font-family);white-space:nowrap;cursor:pointer;transition:color var(--yp-motion-fast) var(--yp-ease-standard),background var(--yp-motion-fast) var(--yp-ease-standard)}
.dashboard-tab span{overflow:hidden;text-overflow:ellipsis}
.dashboard-tab:hover{color:var(--yp-text-primary);background:var(--yp-bg-hover)}
.dashboard-tab.active{color:var(--yp-text-primary)}
.dashboard-tab.active::after{content:'';position:absolute;left:10px;right:10px;bottom:0;height:3px;border-radius:3px 3px 0 0;background:var(--yp-action-primary)}
.dashboard-tab:focus-visible{outline:2px solid var(--yp-focus-ring);outline-offset:-2px}
.dashboard-tab--fixed .el-icon{color:var(--yp-action-primary)}
.dashboard-tabs__divider{flex:none;align-self:center;width:1px;height:20px;margin:0 6px;background:var(--yp-border-default)}
.dashboard-tab-rename{flex:none;align-self:center;width:200px;margin:0 6px}
.dashboard-tabs__action{display:inline-flex;flex:none;align-self:center}
.dashboard-tabs .el-button+.el-button{margin-left:0}
</style>
