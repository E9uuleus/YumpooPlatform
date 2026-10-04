<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElButton, ElCheckbox, ElIcon, ElInput, ElPopover, ElTooltip } from 'element-plus'
import { InfoFilled, Search } from '@element-plus/icons-vue'
import { ProjectLifecycle, type ConnectColumn } from '@yumpoo/api-client'
import type { ApiProblem } from '../../../api/problems'
import { useProjectRecents } from '../../../composables/useProjectRecents'
import { useSession } from '../../../composables/useSession'
import InlineProblem from '../../InlineProblem.vue'
import ConnectBoardsIllustration from './ConnectBoardsIllustration.vue'
import { toConnectProblem } from './connectProblems'
import { useConnectContext } from './useConnectColumns'
import type { ConnectSetupMode, ConnectTargetChoice } from './useConnectTable'

const MAX_TARGETS = 20
// The anchor can sit in a column scrolled past the table edge; let the popover detach and stay on screen.
const popperOptions = { modifiers: [{ name: 'preventOverflow', options: { padding: 16, altAxis: true, tether: false } },
  { name: 'flip', options: { fallbackPlacements: ['bottom-end', 'bottom-start', 'top', 'left'] } }] }
const props = defineProps<{ visible: boolean; mode: ConnectSetupMode; column?: ConnectColumn | undefined; projectId: string; projectName: string;
  submit: (projects: ConnectTargetChoice[]) => Promise<unknown> }>()
const emit = defineEmits<{ 'update:visible': [visible: boolean] }>()
const context = useConnectContext(), session = useSession()
const recents = useProjectRecents(() => {
  const authentication = session.authentication.value
  return authentication ? `${authentication.company.id}:${authentication.user.id}` : undefined
})
const step = ref<'intro' | 'projects'>(props.mode === 'edit' ? 'projects' : 'intro')
const query = ref(''), selected = ref<ConnectTargetChoice[]>([]), results = ref<ConnectTargetChoice[]>([])
const loading = ref(false), submitting = ref(false), page = ref(0), totalPages = ref(0), problem = ref<ApiProblem>()
const search = ref<InstanceType<typeof ElInput>>()
let timer: ReturnType<typeof setTimeout> | undefined, controller: AbortController | undefined, revision = 0
const recent = computed<ConnectTargetChoice[]>(() => query.value.trim() ? [] : recents.items.value
  .filter(project => project.lifecycle === ProjectLifecycle.Active && project.id !== props.projectId)
  .slice(0, 5).map(({ id, code, name }) => ({ id, code, name })))
const others = computed(() => results.value.filter(project => !recent.value.some(item => item.id === project.id)))
const isSelected = (project: ConnectTargetChoice) => selected.value.some(item => item.id === project.id)
const full = computed(() => selected.value.length >= MAX_TARGETS)
const changed = computed(() => props.mode === 'create' || selected.value.map(project => project.id).sort().join()
  !== (props.column?.targets.map(target => target.projectId).sort().join() ?? ''))

function cancelSearch() { revision++; controller?.abort(); clearTimeout(timer); loading.value = false }
async function load(append = false) {
  if (step.value !== 'projects' || (append && (loading.value || page.value + 1 >= totalPages.value))) return
  const current = ++revision; controller?.abort(); controller = new AbortController()
  loading.value = true; problem.value = undefined
  try {
    const result = await context.searchTargets(query.value.trim(), append ? page.value + 1 : 0, controller.signal)
    if (current !== revision) return
    const items = result.items.filter(project => project.id !== props.projectId)
    results.value = append ? [...new Map([...results.value, ...items].map(project => [project.id, project])).values()] : items
    page.value = result.page; totalPages.value = result.totalPages
  } catch (reason) { if (current === revision) problem.value = await toConnectProblem(reason) }
  finally { if (current === revision) loading.value = false }
}
function showProjects() {
  step.value = 'projects'
  void load()
  void nextTick(() => search.value?.focus())
}
watch(() => [props.mode, props.column?.id], () => {
  cancelSearch(); query.value = ''; results.value = []; problem.value = undefined
  selected.value = props.column?.targets.map(target => ({ id: target.projectId, name: target.name, code: target.code })) ?? []
  step.value = props.mode === 'edit' ? 'projects' : 'intro'
  if (step.value === 'projects') void load()
}, { immediate: true })
watch(query, () => { cancelSearch(); results.value = []; page.value = 0; totalPages.value = 0; timer = setTimeout(() => { void load() }, 300) })
onBeforeUnmount(cancelSearch)
function toggle(project: ConnectTargetChoice) {
  if (submitting.value) return
  if (isSelected(project)) selected.value = selected.value.filter(item => item.id !== project.id)
  else if (!full.value) selected.value = [...selected.value, project]
}
function scroll(event: Event) {
  const element = event.currentTarget as HTMLElement
  if (element.scrollTop + element.clientHeight >= element.scrollHeight - 40) void load(true)
}
function close() { if (!submitting.value) emit('update:visible', false) }
async function connect() {
  if (!selected.value.length || submitting.value || !changed.value) return
  submitting.value = true; problem.value = undefined
  try { await props.submit(selected.value) }
  catch (reason) {
    problem.value = await toConnectProblem(reason, {
      projectName: id => selected.value.find(project => project.id === id)?.name ?? props.column?.targets.find(target => target.projectId === id)?.name ?? '目标项目',
    })
  } finally { submitting.value = false }
}
</script>

<template>
  <el-popover
    :visible="visible"
    trigger="click"
    placement="bottom"
    :width="360"
    :show-arrow="false"
    :offset="6"
    :popper-options="popperOptions"
    popper-class="connect-setup-popper"
    @update:visible="value => !value && close()"
  >
    <template #reference>
      <span
        class="connect-setup-anchor"
        aria-hidden="true"
      />
    </template>
    <section
      class="connect-setup"
      :aria-label="mode === 'edit' ? '连接设置' : '连接项目'"
      @keydown.esc.stop.prevent="close"
    >
      <transition
        name="connect-setup-step"
        mode="out-in"
      >
        <div
          v-if="step === 'intro'"
          key="intro"
          class="connect-setup__intro"
        >
          <connect-boards-illustration />
          <h3>连接项目</h3>
          <p>在当前表格中查看和编辑其他项目的工作项。</p>
          <el-button
            type="primary"
            class="connect-setup__primary"
            @click="showProjects"
          >
            选择项目
          </el-button>
        </div>
        <div
          v-else
          key="projects"
          class="connect-setup__projects"
        >
          <header>
            <h3>{{ mode === 'edit' ? '连接设置' : '选择要连接的项目' }}</h3>
            <button
              v-if="mode === 'create'"
              type="button"
              class="connect-setup__back"
              @click="step = 'intro'"
            >
              返回
            </button>
          </header>
          <el-input
            ref="search"
            v-model="query"
            :prefix-icon="Search"
            maxlength="80"
            placeholder="按项目名称或编码搜索"
            aria-label="按项目名称或编码搜索"
            clearable
          />
          <inline-problem
            v-if="problem"
            :problem="problem"
          />
          <div
            class="connect-setup__list"
            :aria-busy="loading"
            @scroll.passive="scroll"
          >
            <template
              v-for="section in [{ key: 'recent', label: '最近使用', items: recent }, { key: 'all', label: query.trim() ? '搜索结果' : '全部项目', items: others }]"
              :key="section.key"
            >
              <template v-if="section.items.length">
                <p class="connect-setup__caption">
                  {{ section.label }}
                </p>
                <div
                  v-for="project in section.items"
                  :key="project.id"
                  class="connect-setup__project"
                  :class="{ 'is-selected': isSelected(project), 'is-disabled': full && !isSelected(project) }"
                  @click="toggle(project)"
                >
                  <el-checkbox
                    :model-value="isSelected(project)"
                    :disabled="submitting || (full && !isSelected(project))"
                    :aria-label="project.name"
                    @click.stop
                    @change="toggle(project)"
                  />
                  <svg
                    class="connect-setup__board"
                    viewBox="0 0 16 16"
                    aria-hidden="true"
                  ><rect
                    x="2"
                    y="2.5"
                    width="12"
                    height="11"
                    rx="2"
                  /><path d="M6 2.5v11" /></svg>
                  <span class="connect-setup__name"><strong>{{ project.name }}</strong><small>{{ project.code }}</small></span>
                </div>
              </template>
            </template>
            <div
              v-if="loading && !results.length"
              class="connect-setup__skeleton"
              role="status"
              aria-label="正在加载项目"
            >
              <span
                v-for="index in 3"
                :key="index"
              />
            </div>
            <p
              v-else-if="!loading && !recent.length && !others.length"
              class="connect-setup__empty"
              role="status"
            >
              没有匹配的项目
            </p>
          </div>
          <div
            class="connect-setup__two-way"
            role="note"
          >
            <svg
              viewBox="0 0 16 16"
              aria-hidden="true"
            ><path d="M2.5 5.5h10m-2.5-2.5 2.5 2.5-2.5 2.5M13.5 10.5h-10m2.5-2.5-2.5 2.5 2.5 2.5" /></svg>
            <span><strong>将创建双向连接</strong>所选项目的表格会自动出现「{{ projectName }}」列</span>
            <el-tooltip
              :content="`两边的成员都能在各自的表格中查看、关联和维护这些连接；从对方项目新建工作项需要是「${projectName}」的成员。`"
              placement="top"
            >
              <el-icon
                class="connect-setup__info"
                tabindex="0"
                aria-label="双向连接说明"
              >
                <info-filled />
              </el-icon>
            </el-tooltip>
          </div>
          <footer>
            <span
              class="connect-setup__count"
              role="status"
            >{{ selected.length ? `已选 ${selected.length} 个项目` : '尚未选择' }}<template v-if="full"> · 已达上限</template></span>
            <el-button
              type="primary"
              :loading="submitting"
              :disabled="!selected.length || !changed"
              @click="connect"
            >
              {{ mode === 'edit' ? '保存' : '连接项目' }}
            </el-button>
          </footer>
        </div>
      </transition>
    </section>
  </el-popover>
</template>

<style scoped>
.connect-setup-anchor { position: absolute; left: 50%; bottom: 0; width: 1px; height: 1px; pointer-events: none; }
.connect-setup { color: var(--yp-text-primary); outline: none; }
.connect-setup h3 { margin: 0; font-family: var(--yp-font-heading); font-size: 15px; font-weight: 600; line-height: 1.4; }
.connect-setup__intro { display: grid; justify-items: center; gap: 10px; padding: 4px 4px 6px; text-align: center; }
.connect-setup__intro > :first-child { justify-self: stretch; margin-bottom: 6px; }
.connect-setup__intro h3 { font-size: 17px; }
.connect-setup__intro p { margin: 0; color: var(--yp-text-secondary); font-size: 13px; line-height: 1.6; }
.connect-setup__primary { min-width: 116px; margin-top: 6px; }
.connect-setup__projects { display: grid; gap: 10px; }
.connect-setup__projects header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.connect-setup__back { padding: 4px 6px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-secondary); font: inherit; font-size: 13px; cursor: pointer; }
.connect-setup__back:hover { background: var(--yp-bg-hover); color: var(--yp-text-primary); }
.connect-setup__list { display: grid; align-content: start; max-height: 260px; min-height: 120px; margin: 0 -6px; padding: 0 6px; overflow-y: auto; }
.connect-setup__caption { margin: 6px 0 2px; color: var(--yp-text-muted); font-size: 12px; }
.connect-setup__project { display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 0 8px; border-radius: var(--yp-radius-sm); cursor: pointer; transition: background-color var(--yp-motion-fast) var(--yp-ease-standard); }
.connect-setup__project:hover { background: var(--yp-bg-hover); }
.connect-setup__project.is-selected { background: color-mix(in srgb, var(--yp-bg-selected) 70%, transparent); }
.connect-setup__project.is-disabled { cursor: not-allowed; opacity: .55; }
.connect-setup__board { width: 16px; height: 16px; flex-shrink: 0; fill: none; stroke: var(--yp-text-secondary); stroke-width: 1.3; }
.connect-setup__name { display: grid; min-width: 0; gap: 1px; }
.connect-setup__name strong { overflow: hidden; font-size: 13px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.connect-setup__name small { color: var(--yp-text-muted); font-size: 11px; }
.connect-setup__skeleton { display: grid; gap: 8px; padding: 8px; }
.connect-setup__skeleton span { height: 28px; border-radius: var(--yp-radius-sm); background: linear-gradient(90deg, var(--yp-bg-sunken), var(--yp-bg-hover), var(--yp-bg-sunken)); background-size: 200% 100%; animation: connect-setup-shimmer 1.2s linear infinite; }
.connect-setup__empty { margin: 16px 0; color: var(--yp-text-muted); font-size: 13px; text-align: center; }
.connect-setup__two-way { display: flex; align-items: flex-start; gap: 8px; padding: 10px; border-radius: var(--yp-radius-sm); background: var(--yp-bg-sunken); color: var(--yp-text-secondary); font-size: 12px; line-height: 1.6; }
.connect-setup__two-way > svg { width: 16px; height: 16px; flex-shrink: 0; margin-top: 2px; fill: none; stroke: var(--yp-link); stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.connect-setup__two-way span { flex: 1; min-width: 0; }
.connect-setup__two-way strong { display: block; color: var(--yp-text-primary); font-size: 12.5px; font-weight: 600; }
.connect-setup__info { flex-shrink: 0; margin-top: 2px; color: var(--yp-text-muted); font-size: 14px; cursor: help; }
.connect-setup__projects footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.connect-setup__count { color: var(--yp-text-muted); font-size: 12px; }
.connect-setup-step-enter-active, .connect-setup-step-leave-active { transition: opacity var(--yp-motion-popover) var(--yp-ease-standard), transform var(--yp-motion-popover) var(--yp-ease-standard); }
.connect-setup-step-enter-from { opacity: 0; transform: translateX(12px); }
.connect-setup-step-leave-to { opacity: 0; transform: translateX(-12px); }
@keyframes connect-setup-shimmer { to { background-position: -200% 0; } }
@media (prefers-reduced-motion: reduce) {
  .connect-setup-step-enter-active, .connect-setup-step-leave-active { transition: none; }
  .connect-setup__skeleton span { animation: none; }
}
</style>

<style>
.connect-setup-popper.el-popover.el-popper { max-width: calc(100vw - 32px); padding: 14px; border-radius: var(--yp-radius-lg); box-sizing: border-box; box-shadow: var(--yp-shadow-popover); }
</style>
