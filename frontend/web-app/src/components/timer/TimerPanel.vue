<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { formatDuration, useTimeTracker } from '../../composables/useTimeTracker'

const props = defineProps<{ projectId?: string | undefined }>()
const desktop = !!window.yumpooDesktop
const route = useRoute()
const router = useRouter()
const tracker = useTimeTracker()
const query = ref('')
const candidates = ref<ProjectWorkItemListItem[]>([])
const recent = computed(() => tracker.current.value?.recentItems ?? [])
const problem = ref('')
const pinned = ref(true)
const switching = ref(false)
let resolveSwitch: ((value: boolean) => void) | undefined
function confirmSwitch(): Promise<boolean> { switching.value = true; return new Promise(resolve => { resolveSwitch = resolve }) }
function decideSwitch(value: boolean) { switching.value = false; resolveSwitch?.(value); resolveSwitch = undefined }
onBeforeUnmount(() => decideSwitch(false))
const desktopProject = ref<string>()
const offProject = window.yumpooDesktop?.timer?.onProject(id => { desktopProject.value = id })
onBeforeUnmount(() => offProject?.())
const context = computed(() => desktopProject.value ?? props.projectId ?? String(route.query.projectId ?? ''))
let timeout: ReturnType<typeof setTimeout> | undefined
let revision = 0
watch([context, query], () => {
  clearTimeout(timeout)
  const request = ++revision
  candidates.value = []
  timeout = setTimeout(async () => {
    if (!context.value) return
    try {
      const page = await workItemsApi.listProjectWorkItems({ projectId: context.value, limit: 25, ...(query.value.trim() ? { q: query.value.trim() } : {}) })
      if (request === revision) { candidates.value = page.items; problem.value = '' }
    } catch { if (request === revision) problem.value = '候选工作项加载失败，请检查网络和项目权限。' }
  }, 250)
}, { immediate: true })
onBeforeUnmount(() => { revision++; clearTimeout(timeout) })
async function start(item: ProjectWorkItemListItem) {
  await tracker.toggle(item.id, confirmSwitch)
}
async function openCurrent() {
  const item = tracker.current.value?.session
  if (!item?.projectId || !item.workItemId) return
  if (window.yumpooDesktop?.timer) await window.yumpooDesktop.timer.openWorkItem(item.projectId, item.workItemId)
  else await router.push({ name: 'project-overview', params: { projectId: item.projectId }, query: { workItemId: item.workItemId } })
}
async function pin() { pinned.value = !pinned.value; await window.yumpooDesktop?.timer.setAlwaysOnTop(pinned.value) }
</script>

<template>
  <section class="timer-panel" aria-label="小计时器">
    <header><strong>小计时器</strong><el-button v-if="desktop" text @click="pin">{{ pinned ? '取消置顶' : '置顶' }}</el-button></header>
    <div v-if="tracker.current.value?.session" class="active-timer">
      <span>● 计时中</span><strong>{{ tracker.current.value.workItemTitle ?? '不可见工作项' }}</strong>
      <output>{{ formatDuration(tracker.runningDuration.value) }}</output>
      <div><el-button type="primary" :disabled="tracker.busy.value" @click="tracker.stop().catch(() => { problem = '停止失败，请重试。' })">停止计时</el-button><el-button v-if="tracker.current.value.session.workItemId" @click="openCurrent">打开工作项</el-button></div>
    </div>
    <p v-else>尚未计时，选择工作项开始。</p>
    <div v-if="switching" role="alertdialog" aria-label="切换计时" class="switch-confirm">
      <p>停止当前计时并开始选中的工作项？</p><el-button @click="decideSwitch(false)">取消</el-button><el-button type="primary" @click="decideSwitch(true)">停止并切换</el-button>
    </div>
    <p v-if="problem" role="alert">{{ problem }}</p>
    <label>搜索当前项目 <input v-model="query" type="search" placeholder="工作项名称或编号"></label>
    <p v-if="!context">进入项目后可选择工作项。</p>
    <template v-if="recent.length && !query"><h4>最近计时</h4><button v-for="item in recent" :key="item.workItemId" class="candidate" @click="tracker.toggle(item.workItemId, confirmSwitch)">{{ item.title }} <span>▶</span></button></template>
    <h4>当前项目</h4><button v-for="item in candidates" :key="item.id" class="candidate" @click="start(item)">{{ item.title }} <span>{{ tracker.current.value?.session?.workItemId === item.id ? '■' : '▶' }}</span></button>
  </section>
</template>

<style scoped>
.timer-panel{padding:20px;min-width:280px;max-height:90vh;overflow:auto;background:var(--el-bg-color,#fff);color:var(--el-text-color-primary,#222)}header{display:flex;justify-content:space-between;align-items:center}.active-timer{display:grid;gap:12px;padding:18px 0}.active-timer>span{color:var(--el-color-success,#27844b)}output{font-size:32px;font-variant-numeric:tabular-nums}.candidate{width:100%;display:flex;justify-content:space-between;padding:12px 4px;background:transparent;color:inherit;border:0;border-bottom:1px solid var(--el-border-color,#ddd);cursor:pointer;text-align:left}.candidate:hover{background:var(--el-fill-color-light,#f7f7f7)}label{display:grid;gap:8px;margin:15px 0}input{padding:9px;border:1px solid var(--el-border-color,#ddd);border-radius:6px;background:inherit;color:inherit}h4{margin:16px 0 4px}
</style>
