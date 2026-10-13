<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElButton, ElIcon, ElInput, ElPopover, ElTooltip } from 'element-plus'
import { Check, DataAnalysis, Menu, Plus, Search } from '@element-plus/icons-vue'
import type { DashboardSummary } from '@yumpoo/api-client'
import YpEmptyState from '../yp/YpEmptyState.vue'
import { useSlidingIndicator } from '../yp/useSlidingIndicator'

interface Tab { id: string; name: string }

const props = defineProps<{ dashboards: DashboardSummary[]; activeId: string; activeName: string; renamable: boolean }>()
const emit = defineEmits<{ select: [id: string]; create: []; rename: [name: string] }>()
const strip = ref<HTMLElement>()
const listOpen = ref(false), search = ref(''), editing = ref(false), draft = ref('')
const tabs = computed<Tab[]>(() => props.dashboards.map(d => ({ id: d.id, name: d.id === props.activeId ? props.activeName : d.name })))
const choices = computed(() => tabs.value.filter(tab => tab.name.toLowerCase().includes(search.value.trim().toLowerCase())))
const activeIndex = computed(() => editing.value ? -1 : tabs.value.findIndex(tab => tab.id === props.activeId))
const { indicatorStyle, animated, measure } = useSlidingIndicator(strip, '.dashboard-tab', () => activeIndex.value)
const hover = reactive({ left: 0, width: 0, shown: false, sliding: false })

function hoverTab(event: PointerEvent) {
  const tab = event.currentTarget as HTMLElement
  // The pill slides only between tabs; entering the strip fades it in at the hovered tab.
  hover.sliding = hover.shown
  Object.assign(hover, { left: tab.offsetLeft, width: tab.offsetWidth, shown: true })
}

function select(id: string) { listOpen.value = false; if (id !== props.activeId) emit('select', id) }
function create() { listOpen.value = false; emit('create') }
async function startRename(tab: Tab) {
  if (tab.id !== props.activeId || !props.renamable) return
  draft.value = tab.name; editing.value = true; hover.shown = false
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
  measure()
  strip.value?.querySelector('.dashboard-tab.active')?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}, { immediate: true })
watch(() => props.activeName, () => { void nextTick(measure) })
</script>

<template>
  <div class="dashboard-tabs">
    <div
      ref="strip"
      class="dashboard-tabs__strip"
      role="tablist"
      aria-label="仪表板"
      @wheel="scrollStrip"
      @pointerleave="hover.shown = false"
    >
      <span
        class="dashboard-tabs__hover"
        :class="{ 'is-shown': hover.shown, 'is-sliding': hover.sliding }"
        :style="{ width: `${hover.width}px`, transform: `translateX(${hover.left}px)` }"
        aria-hidden="true"
      />
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
          :class="{ active: tab.id === activeId }"
          type="button"
          role="tab"
          :aria-selected="tab.id === activeId"
          :tabindex="tab.id === activeId ? 0 : -1"
          :title="tab.name"
          @click="select(tab.id)"
          @dblclick="startRename(tab)"
          @keydown="move($event, index)"
          @pointerenter="hoverTab"
        >
          <span>{{ tab.name }}</span>
        </button>
      </template>
      <span
        class="dashboard-tabs__ink"
        :class="{ 'is-animated': animated }"
        :style="indicatorStyle"
        aria-hidden="true"
      />
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
            <DataAnalysis />
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
.dashboard-tabs__strip{position:relative;display:flex;align-items:stretch;min-width:0;overflow-x:auto;overflow-y:hidden;scrollbar-width:none}
.dashboard-tabs__strip::-webkit-scrollbar{display:none}
.dashboard-tab{position:relative;display:inline-flex;align-items:center;gap:6px;flex:none;max-width:240px;padding:0 14px;border:0;border-radius:6px 6px 0 0;background:none;color:var(--yp-text-secondary);font:500 15px/22px var(--yp-font-family);white-space:nowrap;cursor:pointer;transition:color var(--yp-motion-fast) var(--yp-ease-standard),background var(--yp-motion-fast) var(--yp-ease-standard)}
.dashboard-tab span{overflow:hidden;text-overflow:ellipsis}
.dashboard-tab:hover{color:var(--yp-text-primary)}
.dashboard-tab.active{color:var(--yp-text-primary)}
.dashboard-tabs__hover{position:absolute;top:8px;bottom:8px;left:0;border-radius:8px;background:var(--yp-bg-hover);opacity:0;pointer-events:none;transition:opacity var(--yp-motion-fast) var(--yp-ease-standard)}
.dashboard-tabs__hover.is-shown{opacity:1}
.dashboard-tabs__hover.is-sliding{transition:opacity var(--yp-motion-fast) var(--yp-ease-standard),transform var(--yp-motion-overlay) var(--yp-ease-standard),width var(--yp-motion-overlay) var(--yp-ease-standard)}
.dashboard-tabs__ink{position:absolute;bottom:0;left:0;height:3px;pointer-events:none}
.dashboard-tabs__ink::after{content:'';position:absolute;inset:0 10px;border-radius:3px 3px 0 0;background:var(--yp-action-primary)}
.dashboard-tabs__ink.is-animated{transition:transform var(--yp-motion-progress) var(--yp-ease-standard),width var(--yp-motion-progress) var(--yp-ease-standard)}
.dashboard-tab:focus-visible{outline:2px solid var(--yp-focus-ring);outline-offset:-2px}
.dashboard-tab-rename{flex:none;align-self:center;width:200px;margin:0 6px}
.dashboard-tabs__action{display:inline-flex;flex:none;align-self:center}
.dashboard-tabs .el-button+.el-button{margin-left:0}
@media (prefers-reduced-motion:reduce){.dashboard-tabs__hover,.dashboard-tabs__hover.is-sliding,.dashboard-tabs__ink,.dashboard-tabs__ink.is-animated{transition:none}}
</style>
