<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElButton, ElInput, ElPopover } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import { useTimerCandidates } from '../../../composables/useTimerCandidates'
import { workItemLabelColorValue } from '../../projects/workItemLabelColors'
import { durationLabel, HOUR, MINUTE, parseDuration, type ItemTotal } from './dayModel'

export interface ShelfItem { workItemId: string; title: string; itemNo: string | null; projectName: string | null; colorToken: string | null }

const props = defineProps<{ totals: ItemTotal[]; busy: boolean }>()
const emit = defineEmits<{ place: [item: ShelfItem, durationMs: number]; preview: [item: ShelfItem | null, durationMs: number] }>()

const presets = [30 * MINUTE, HOUR, 2 * HOUR, 4 * HOUR]
const { query, items, loading, problem, nextOffset, searching, load } = useTimerCandidates(ref(true))
const recorded = computed(() => new Set(props.totals.map(item => item.workItemId)))
const candidates = computed<ShelfItem[]>(() => items.value
  .filter(item => searching.value || !recorded.value.has(item.workItemId))
  .map(item => ({ workItemId: item.workItemId, title: item.title, itemNo: item.itemNo, projectName: item.projectName,
    colorToken: item.contentColorToken ?? null })))
const openFor = ref<string>()
const popovers = new Map<string, { hide: () => void }>()
function register(id: string, popover: unknown) {
  if (popover) popovers.set(id, popover as { hide: () => void })
  else popovers.delete(id)
}
const custom = ref('')
const customMs = computed(() => parseDuration(custom.value))

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
const color = (item: ShelfItem) => ({ '--shelf-item-color': workItemLabelColorValue(item.colorToken ?? undefined) })
</script>

<template>
  <aside
    class="item-shelf"
    aria-label="工作项"
  >
    <el-input
      v-model="query"
      :prefix-icon="Search"
      placeholder="搜索工作项或项目"
      maxlength="200"
      clearable
      aria-label="搜索工作项"
    />
    <section
      v-for="section in searching
        ? [{ key: 'search', title: '搜索结果', list: candidates }]
        : [{ key: 'today', title: '今天已记录', list: totals }, { key: 'recent', title: '最近与我的', list: candidates }]"
      :key="section.key"
      class="item-shelf__section"
    >
      <h2>{{ section.title }}</h2>
      <p
        v-if="section.key === 'today' && !totals.length"
        class="item-shelf__empty"
      >
        还没有记录。在右侧拖选一段时间，或点工作项旁的 + 按时长排入空白。
      </p>
      <ul v-else>
        <li
          v-for="item in section.list"
          :key="item.workItemId"
          class="item-shelf__row"
          :class="{ 'is-open': openFor === item.workItemId }"
          :style="color(item)"
        >
          <span class="item-shelf__text">
            <strong>{{ item.title }}</strong>
            <small>{{ item.itemNo ? `#${item.itemNo} · ` : '' }}{{ item.projectName ?? '' }}</small>
          </span>
          <span
            v-if="'ms' in item"
            class="item-shelf__ms"
          >{{ durationLabel(item.ms) }}</span>
          <el-popover
            :ref="popover => register(item.workItemId, popover)"
            trigger="click"
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
                placeholder="其他时长，如 1.5 或 1h30"
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
          {{ searching ? '没有匹配的工作项' : '输入关键字搜索全部工作项' }}
        </p>
        <el-button
          v-else-if="nextOffset !== null"
          text
          size="small"
          @click="load(true)"
        >
          加载更多
        </el-button>
      </template>
    </section>
  </aside>
</template>

<style scoped>
.item-shelf{display:flex;flex-direction:column;gap:14px;min-height:0;overflow:auto;padding:12px;border:1px solid var(--yp-border-subtle);border-radius:var(--yp-radius-md);background:var(--yp-bg-surface)}
.item-shelf__section h2{margin:0 0 4px;color:var(--yp-text-muted);font-size:12px;font-weight:500}
.item-shelf__section ul{display:grid;gap:1px;margin:0;padding:0;list-style:none}
.item-shelf__empty{margin:0;color:var(--yp-text-muted);font-size:12px;line-height:18px}
.item-shelf__row{position:relative;display:flex;align-items:center;gap:6px;min-height:40px;padding:3px 4px 3px 12px;border-radius:var(--yp-radius-sm);transition:background var(--yp-motion-fast) ease-out}
.item-shelf__row:hover,.item-shelf__row.is-open{background:var(--yp-bg-selected)}
.item-shelf__row::before{content:'';position:absolute;top:8px;bottom:8px;left:3px;width:3px;border-radius:2px;background:var(--shelf-item-color)}
.item-shelf__text{display:grid;flex:1;min-width:0;gap:1px}
.item-shelf__text strong{overflow:hidden;color:var(--yp-text-primary);font-size:13px;font-weight:500;white-space:nowrap;text-overflow:ellipsis}
.item-shelf__text small{overflow:hidden;color:var(--yp-text-muted);font-size:11px;white-space:nowrap;text-overflow:ellipsis}
.item-shelf__ms{color:var(--yp-text-secondary);font:600 12px var(--yp-font-mono);font-variant-numeric:tabular-nums}
.item-shelf__add{flex:none;opacity:.55}
.item-shelf__row:hover .item-shelf__add,.item-shelf__row.is-open .item-shelf__add,.item-shelf__add:focus-visible{opacity:1}
.shelf-duration{display:grid;gap:8px}
.shelf-duration p{margin:0;color:var(--yp-text-muted);font-size:12px}
.shelf-duration__chips{display:grid;grid-template-columns:repeat(4,1fr);gap:4px}
.shelf-duration__chips .el-button+.el-button{margin-left:0}
</style>
