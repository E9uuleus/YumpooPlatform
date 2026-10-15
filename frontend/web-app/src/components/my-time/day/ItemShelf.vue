<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElButton, ElIcon, ElInput, ElPopover } from 'element-plus'
import { FolderOpened, Plus, Search, User } from '@element-plus/icons-vue'
import { ListTimerCandidatesScopeEnum } from '@yumpoo/api-client'
import { useTimerCandidates } from '../../../composables/useTimerCandidates'
import '../../projects/workItemAccentBar.css'
import { workItemLabelColorValue } from '../../projects/workItemLabelColors'
import YpSegmented from '../../yp/YpSegmented.vue'
import { durationLabel, HOUR, MIN_BLOCK_MS, MINUTE, parseDuration, type ItemTotal } from './dayModel'
import { draggedItem, SHELF_DRAG_TYPE, type ShelfItem } from './shelfDrag'

const props = defineProps<{ totals: ItemTotal[]; busy: boolean }>()
const emit = defineEmits<{ place: [item: ShelfItem, durationMs: number]; preview: [item: ShelfItem | null, durationMs: number] }>()

const presets = [30 * MINUTE, HOUR, 2 * HOUR, 4 * HOUR]
const scopes = [{ value: ListTimerCandidatesScopeEnum.Personal, label: '最近与我的' }, { value: ListTimerCandidatesScopeEnum.All, label: '全部' }]
const { query, scope, items, loading, problem, nextOffset, searching, load } = useTimerCandidates(ref(true))
const mine = ref(false)
const projects = ref(new Set<string>())
const projectOptions = computed(() => [...items.value.reduce((options, item) => {
  const option = options.get(item.projectId) ?? { id: item.projectId, name: item.projectName, count: 0 }
  option.count++
  return options.set(item.projectId, option)
}, new Map<string, { id: string; name: string; count: number }>()).values()].sort((a, b) => b.count - a.count))
const filtering = computed(() => searching.value || scope.value !== ListTimerCandidatesScopeEnum.Personal || mine.value || projects.value.size > 0)
const recorded = computed(() => new Set(props.totals.map(item => item.workItemId)))
const candidates = computed<ShelfItem[]>(() => items.value
  .filter(item => (filtering.value || !recorded.value.has(item.workItemId)) && (!mine.value || item.assignedToMe)
    && (!projects.value.size || projects.value.has(item.projectId)))
  .map(item => ({ workItemId: item.workItemId, title: item.title, itemNo: item.itemNo, projectName: item.projectName,
    colorToken: item.contentColorToken ?? null, contentName: item.contentName })))
interface ShelfSection { key: string; title: string; list: (ShelfItem & { ms?: number | undefined })[] }
const sections = computed<ShelfSection[]>(() => filtering.value
  ? [{ key: 'result', title: searching.value ? '搜索结果' : '筛选结果', list: candidates.value }]
  : [{ key: 'today', title: '今天已记录', list: props.totals }, { key: 'recent', title: '最近与我的', list: candidates.value }])
function toggleProject(id: string) {
  const next = new Set(projects.value)
  if (!next.delete(id)) next.add(id)
  projects.value = next
}
function clearFilters() {
  query.value = ''
  scope.value = ListTimerCandidatesScopeEnum.Personal
  mine.value = false
  projects.value = new Set()
}

const openFor = ref<string>()
const popovers = new Map<string, { hide: () => void }>()
function register(id: string, popover: unknown) {
  if (popover) popovers.set(id, popover as { hide: () => void })
  else popovers.delete(id)
}
const custom = ref('')
const customMs = computed(() => {
  const ms = parseDuration(custom.value)
  return ms !== null && ms >= MIN_BLOCK_MS ? ms : null
})

function open(item: ShelfItem, visible: boolean) {
  if (visible) { openFor.value = item.workItemId; custom.value = '' }
  else if (openFor.value === item.workItemId) { openFor.value = undefined; emit('preview', null, 0) }
}
function place(item: ShelfItem, durationMs: number | null) {
  if (!durationMs || props.busy) return
  popovers.get(item.workItemId)?.hide()
  emit('preview', null, 0)
  emit('place', item, durationMs)
}
/** Touch screens scroll the shelf instead; they use the + button. */
const canDrag = typeof window !== 'undefined' && !window.matchMedia?.('(pointer: coarse)').matches
const dragging = ref<string>()
function dragStart(item: ShelfItem, event: DragEvent) {
  draggedItem.value = item
  dragging.value = item.workItemId
  event.dataTransfer?.setData(SHELF_DRAG_TYPE, item.workItemId)
  event.dataTransfer?.setData('text/plain', item.title)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
}
function dragEnd() { draggedItem.value = null; dragging.value = undefined }
const color = (item: ShelfItem) => ({ '--work-item-accent': workItemLabelColorValue(item.colorToken ?? undefined) })
</script>

<template>
  <aside
    class="item-shelf"
    aria-label="工作项"
  >
    <header class="item-shelf__head">
      <div class="item-shelf__heading">
        <h2>工作项</h2>
        <small>{{ canDrag ? '拖到右侧时间线，或点 + 按时长排入' : '点 + 按时长排入空白' }}</small>
      </div>
      <el-input
        v-model="query"
        :prefix-icon="Search"
        placeholder="搜索工作项或项目"
        maxlength="200"
        clearable
        aria-label="搜索工作项"
      />
      <div
        class="item-shelf__filters"
        role="group"
        aria-label="筛选工作项"
      >
        <YpSegmented
          v-model="scope"
          :options="scopes"
          label="范围"
        />
        <button
          type="button"
          class="item-shelf__filter"
          :class="{ active: mine }"
          :aria-pressed="mine"
          @click="mine = !mine"
        >
          <el-icon><User /></el-icon><span>分配给我</span>
        </button>
        <el-popover
          trigger="click"
          placement="bottom-start"
          :width="260"
          popper-class="day-popover"
        >
          <template #reference>
            <button
              type="button"
              class="item-shelf__filter"
              :class="{ active: projects.size }"
            >
              <el-icon><FolderOpened /></el-icon><span>项目</span>
              <span
                v-if="projects.size"
                class="item-shelf__count"
              >{{ projects.size }}</span>
            </button>
          </template>
          <div class="item-shelf-projects">
            <header>
              <strong>按项目筛选</strong>
              <button
                v-if="projects.size"
                type="button"
                class="item-shelf__clear"
                @click="projects = new Set()"
              >
                清除
              </button>
            </header>
            <button
              v-for="project in projectOptions"
              :key="project.id"
              type="button"
              class="item-shelf-projects__option"
              :aria-pressed="projects.has(project.id)"
              @click="toggleProject(project.id)"
            >
              <span
                class="item-shelf-projects__check"
                :class="{ checked: projects.has(project.id) }"
                aria-hidden="true"
              />
              <span class="item-shelf-projects__name">{{ project.name }}</span>
              <small>{{ project.count }}</small>
            </button>
            <p
              v-if="!projectOptions.length"
              class="item-shelf__empty"
            >
              当前列表中没有项目
            </p>
          </div>
        </el-popover>
        <button
          v-if="filtering"
          type="button"
          class="item-shelf__clear"
          @click="clearFilters"
        >
          清除
        </button>
      </div>
    </header>
    <div class="item-shelf__body">
      <section
        v-for="section in sections"
        :key="section.key"
        class="item-shelf__section"
      >
        <h3>{{ section.title }}<small v-if="section.list.length">{{ section.list.length }}</small></h3>
        <p
          v-if="section.key === 'today' && !totals.length"
          class="item-shelf__empty"
        >
          还没有记录。把下方工作项拖到右侧时间线，或在时间线上拖选一段时间。
        </p>
        <ul v-else>
          <li
            v-for="item in section.list"
            :key="item.workItemId"
            class="item-shelf__row work-item-accent-bar"
            :class="{ 'is-open': openFor === item.workItemId, 'is-draggable': canDrag, 'is-dragging': dragging === item.workItemId }"
            :style="color(item)"
            :draggable="canDrag"
            :title="canDrag ? '拖到右侧时间线即可添加 1 小时' : undefined"
            @dragstart="dragStart(item, $event)"
            @dragend="dragEnd"
          >
            <span
              v-if="canDrag"
              class="item-shelf__grip"
              aria-hidden="true"
            />
            <span class="item-shelf__text">
              <strong>{{ item.title }}</strong>
              <small v-if="item.projectName">{{ item.projectName }}</small>
            </span>
            <span
              v-if="item.contentName"
              class="item-shelf__category"
            >{{ item.contentName }}</span>
            <span
              v-if="item.ms !== undefined"
              class="item-shelf__ms"
            >{{ durationLabel(item.ms) }}</span>
            <el-popover
              :ref="popover => register(item.workItemId, popover)"
              trigger="click"
              :persistent="false"
              placement="right-start"
              :width="232"
              popper-class="day-popover"
              @show="open(item, true)"
              @hide="open(item, false)"
            >
              <template #reference>
                <el-button
                  text
                  circle
                  size="small"
                  class="item-shelf__add"
                  :icon="Plus"
                  :aria-label="`按时长补录 ${item.title}`"
                  :aria-expanded="openFor === item.workItemId"
                />
              </template>
              <div class="shelf-duration">
                <p>排入上班时间后的空白</p>
                <div class="shelf-duration__chips">
                  <el-button
                    v-for="preset in presets"
                    :key="preset"
                    size="small"
                    :disabled="busy"
                    @mouseenter="emit('preview', item, preset)"
                    @focus="emit('preview', item, preset)"
                    @click="place(item, preset)"
                  >
                    {{ durationLabel(preset) }}
                  </el-button>
                </div>
                <el-input
                  v-model="custom"
                  size="small"
                  placeholder="其他时长（至少 15 分钟），如 1.5 或 1h30"
                  aria-label="自定义时长"
                  @input="emit('preview', customMs ? item : null, customMs ?? 0)"
                  @keydown.enter.prevent="place(item, customMs)"
                />
              </div>
            </el-popover>
          </li>
        </ul>
        <template v-if="section.key !== 'today'">
          <p
            v-if="loading"
            class="item-shelf__empty"
          >
            正在加载…
          </p>
          <p
            v-else-if="problem"
            class="item-shelf__empty"
            role="alert"
          >
            {{ problem }}<el-button
              text
              size="small"
              @click="load()"
            >
              重试
            </el-button>
          </p>
          <p
            v-else-if="!section.list.length"
            class="item-shelf__empty"
          >
            {{ filtering ? '没有匹配的工作项' : '输入关键字搜索全部工作项' }}
          </p>
          <el-button
            v-if="!loading && !problem && nextOffset !== null"
            text
            size="small"
            @click="load(true)"
          >
            加载更多
          </el-button>
        </template>
      </section>
    </div>
  </aside>
</template>

<style scoped>
.item-shelf{display:flex;flex-direction:column;min-height:0;overflow:hidden;border:1px solid var(--yp-border-subtle);border-radius:var(--yp-radius-md);background:var(--yp-bg-surface)}
.item-shelf__head{display:grid;gap:10px;padding:14px 14px 12px;border-bottom:1px solid var(--yp-border-subtle)}
.item-shelf__heading{display:flex;align-items:baseline;justify-content:space-between;gap:8px;min-width:0}
.item-shelf__heading h2{margin:0;color:var(--yp-text-primary);font:600 15px/22px var(--yp-font-heading)}
.item-shelf__heading small{overflow:hidden;color:var(--yp-text-muted);font-size:12px;white-space:nowrap;text-overflow:ellipsis}
.item-shelf__filters{display:flex;flex-wrap:wrap;align-items:center;gap:4px}
.item-shelf__filter{display:inline-flex;align-items:center;gap:6px;height:30px;padding:0 10px;border:0;border-radius:var(--yp-radius-sm);background:transparent;color:var(--yp-text-primary);font:13px var(--yp-font-family);cursor:pointer;transition:background var(--yp-motion-fast) var(--yp-ease-standard),color var(--yp-motion-fast) var(--yp-ease-standard)}
.item-shelf__filter:hover,.item-shelf__filter.active{background:var(--yp-bg-hover);color:var(--yp-action-primary)}
.item-shelf__count{min-width:18px;padding:1px 5px;box-sizing:border-box;border-radius:var(--yp-radius-pill);background:var(--yp-action-primary);color:var(--yp-text-inverse);font-size:11px;line-height:14px;text-align:center}
.item-shelf__clear{margin-left:auto;padding:4px 8px;border:0;border-radius:var(--yp-radius-sm);background:transparent;color:var(--yp-action-primary);font:13px var(--yp-font-family);cursor:pointer}
.item-shelf__clear:hover{background:var(--yp-bg-hover)}
.item-shelf__body{display:flex;flex-direction:column;gap:16px;min-height:0;overflow:auto;padding:12px 14px 14px;overscroll-behavior:contain}
.item-shelf__section h3{display:flex;align-items:center;gap:6px;margin:0 0 8px;color:var(--yp-text-secondary);font-size:13px;font-weight:600}
.item-shelf__section h3 small{color:var(--yp-text-muted);font:500 12px var(--yp-font-mono)}
.item-shelf__section ul{display:grid;gap:6px;margin:0;padding:0;list-style:none}
.item-shelf__empty{margin:0;color:var(--yp-text-muted);font-size:13px;line-height:20px}
.item-shelf__row{display:flex;align-items:center;gap:8px;min-height:52px;padding:6px 6px 6px 16px;border:1px solid var(--yp-monday-grid-border);border-radius:var(--yp-radius-sm);background:var(--yp-bg-surface);box-shadow:var(--yp-shadow-card);transition:box-shadow var(--yp-motion-fast) ease-out,border-color var(--yp-motion-fast) ease-out,opacity var(--yp-motion-fast) ease-out}
.item-shelf__row.work-item-accent-bar::before{top:-1px;bottom:-1px;left:-1px}
.item-shelf__row:hover,.item-shelf__row.is-open{border-color:color-mix(in srgb,var(--work-item-accent) 45%,var(--yp-monday-grid-border));box-shadow:var(--yp-shadow-hover)}
.item-shelf__row.is-draggable{cursor:grab}
.item-shelf__row.is-draggable:active{cursor:grabbing}
.item-shelf__row.is-dragging{border-style:dashed;box-shadow:none;opacity:.45}
.item-shelf__grip{flex:none;width:8px;height:16px;margin-left:-6px;background:radial-gradient(circle,var(--yp-text-muted) 1.2px,transparent 1.5px) 0 0/4px 5.33px;opacity:0;transition:opacity var(--yp-motion-fast) ease-out}
.item-shelf__row:hover .item-shelf__grip{opacity:.7}
.item-shelf__text{display:grid;flex:1;min-width:0;gap:2px}
.item-shelf__text strong{overflow:hidden;color:var(--yp-text-primary);font-size:14px;font-weight:500;line-height:20px;white-space:nowrap;text-overflow:ellipsis}
.item-shelf__text small{overflow:hidden;color:var(--yp-text-secondary);font-size:13px;line-height:18px;white-space:nowrap;text-overflow:ellipsis}
.item-shelf__category{flex:none;max-width:88px;overflow:hidden;padding:2px 8px;border-radius:var(--yp-radius-pill);background:color-mix(in srgb,var(--work-item-accent) 14%,var(--yp-bg-surface));color:var(--yp-text-secondary);font-size:12px;line-height:16px;white-space:nowrap;text-overflow:ellipsis}
.item-shelf__ms{flex:none;color:var(--yp-text-primary);font:600 13px var(--yp-font-mono);font-variant-numeric:tabular-nums}
.item-shelf__add{flex:none;opacity:.55}
.item-shelf__row:hover .item-shelf__add,.item-shelf__row.is-open .item-shelf__add,.item-shelf__add:focus-visible{opacity:1}
.item-shelf-projects{display:grid;gap:2px}
.item-shelf-projects header{display:flex;align-items:center;justify-content:space-between;min-height:26px;margin-bottom:6px}
.item-shelf-projects header strong{color:var(--yp-text-primary);font-size:13px;font-weight:600}
.item-shelf-projects .item-shelf__clear{margin-left:0}
.item-shelf-projects__option{display:flex;align-items:center;gap:8px;width:100%;padding:7px 8px;border:0;border-radius:var(--yp-radius-sm);background:transparent;color:var(--yp-text-primary);font:13px var(--yp-font-family);text-align:left;cursor:pointer}
.item-shelf-projects__option:hover{background:var(--yp-bg-hover)}
.item-shelf-projects__name{flex:1;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.item-shelf-projects__option small{color:var(--yp-text-muted)}
.item-shelf-projects__check{display:inline-grid;flex:none;place-items:center;width:14px;height:14px;box-sizing:border-box;border:1px solid var(--yp-border-default);border-radius:3px}
.item-shelf-projects__check.checked{border-color:var(--yp-action-primary);background:var(--yp-action-primary);color:var(--yp-text-inverse)}
.item-shelf-projects__check.checked::after{content:'✓';font-size:11px;line-height:1}
.shelf-duration{display:grid;gap:8px}
.shelf-duration p{margin:0;color:var(--yp-text-muted);font-size:12px}
.shelf-duration__chips{display:grid;grid-template-columns:repeat(4,1fr);gap:4px}
.shelf-duration__chips .el-button+.el-button{margin-left:0}
</style>
