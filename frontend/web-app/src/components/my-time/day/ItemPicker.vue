<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useId, watch } from 'vue'
import { ElButton, ElInput } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import type { TimerCandidate } from '@yumpoo/api-client'
import { useTimerCandidates } from '../../../composables/useTimerCandidates'
import { useInfiniteSentinel } from '../../../composables/useInfiniteSentinel'
import { useHideCompleted } from '../../../composables/useHideCompleted'
import HideCompletedSwitch from '../../timer/HideCompletedSwitch.vue'
import WorkItemGlyph from '../../timer/WorkItemGlyph.vue'
import { MAX_PARALLEL_ITEMS } from './dayModel'

const props = withDefaults(defineProps<{ selected?: TimerCandidate[]; multiple?: boolean; exclude?: (string | null)[]; placeholder?: string }>(),
  { selected: () => [], multiple: false, exclude: () => [], placeholder: '搜索工作项或项目' })
const emit = defineEmits<{ pick: [item: TimerCandidate]; 'update:selected': [items: TimerCandidate[]]; escape: [] }>()

const input = ref<InstanceType<typeof ElInput>>()
const listbox = ref<HTMLElement>()
const id = useId()
const { query, items, hiddenCount, loading, problem, nextOffset, searching, load } = useTimerCandidates(ref(true))
const { hideCompleted } = useHideCompleted()
const sentinel = ref<HTMLElement>()
useInfiniteSentinel(listbox, sentinel, () => items.value.length,
  () => nextOffset.value !== null && !loading.value && !problem.value, () => load(true))
const visible = items
const options = computed(() => visible.value.filter(item => !props.exclude.includes(item.workItemId)))
const active = ref(0)
const picking = computed(() => props.multiple && props.selected.length > 0)
const isSelected = (item: TimerCandidate) => props.selected.some(selected => selected.workItemId === item.workItemId)
watch(options, () => { active.value = 0 })

function toggle(item: TimerCandidate) {
  if (isSelected(item)) emit('update:selected', props.selected.filter(selected => selected.workItemId !== item.workItemId))
  else if (props.selected.length < MAX_PARALLEL_ITEMS) emit('update:selected', [...props.selected, item])
}

/** A plain click saves one item; once anything is checked, clicks keep adding to the parallel selection instead. */
function choose(item: TimerCandidate, event?: MouseEvent | KeyboardEvent) {
  if (props.multiple && (event?.ctrlKey || event?.metaKey || picking.value)) toggle(item)
  else emit('pick', item)
}

async function move(step: number) {
  if (!options.value.length) return
  active.value = (active.value + step + options.value.length) % options.value.length
  await nextTick()
  listbox.value?.querySelector<HTMLElement>(`[data-index="${active.value}"]`)?.scrollIntoView({ block: 'nearest' })
}

function keydown(raw: Event | KeyboardEvent) {
  const event = raw as KeyboardEvent
  if (event.key === 'ArrowDown') { event.preventDefault(); void move(1) }
  else if (event.key === 'ArrowUp') { event.preventDefault(); void move(-1) }
  else if (event.key === 'Enter' && !event.isComposing) {
    event.preventDefault()
    const item = options.value[active.value]
    if (item) choose(item, event)
  } else if (event.key === 'Escape') {
    event.preventDefault()
    if (query.value) query.value = ''
    else emit('escape')
  }
}

onMounted(() => { void nextTick(() => input.value?.focus()) })
defineExpose({ focus: () => input.value?.focus() })
</script>

<template>
  <div class="item-picker">
    <el-input
      ref="input"
      v-model="query"
      :prefix-icon="Search"
      :placeholder="placeholder"
      maxlength="200"
      clearable
      role="combobox"
      aria-expanded="true"
      aria-autocomplete="list"
      :aria-controls="`${id}-list`"
      :aria-activedescendant="options.length ? `${id}-${active}` : undefined"
      @keydown="keydown"
    />
    <div class="item-picker__label">
      <span>{{ searching ? '搜索结果' : '最近与我的' }}</span>
      <HideCompletedSwitch :hidden-count="hiddenCount" />
    </div>
    <ul
      :id="`${id}-list`"
      ref="listbox"
      class="item-picker__list"
      role="listbox"
      :aria-multiselectable="multiple"
      aria-label="工作项"
    >
      <li
        v-for="(item, index) in options"
        :id="`${id}-${index}`"
        :key="item.workItemId"
        :data-index="index"
        role="option"
        class="item-picker__option"
        :class="{ 'is-active': index === active, 'is-selected': isSelected(item), 'is-completed': hideCompleted && item.completed }"
        :aria-selected="isSelected(item)"
        @mousedown.prevent
        @mouseenter="active = index"
        @click="choose(item, $event)"
      >
        <span
          v-if="multiple"
          class="item-picker__check"
          role="checkbox"
          :aria-checked="isSelected(item)"
          :aria-label="`多选 ${item.title}`"
          @click.stop="toggle(item)"
        />
        <WorkItemGlyph
          :code="item.contentCode"
          :name="item.contentName"
          :color-token="item.contentColorToken"
        />
        <span class="item-picker__text">
          <strong>{{ item.title }}</strong>
          <small>{{ item.projectName }}</small>
        </span>
        <span
          v-if="hideCompleted && item.completed"
          class="item-picker__done"
        >已完成</span>
      </li>
      <div
        v-if="nextOffset !== null"
        ref="sentinel"
        class="item-picker-sentinel"
        role="presentation"
        aria-hidden="true"
      />
    </ul>
    <p
      v-if="loading && !items.length"
      class="item-picker__status"
    >
      正在加载…
    </p>
    <p
      v-else-if="problem && !items.length"
      class="item-picker__status"
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
      v-else-if="!options.length"
      class="item-picker__status"
    >
      {{ searching ? '没有匹配的工作项' : '还没有分配给你或最近记录过的工作项，输入关键字搜索全部。' }}
    </p>
    <p
      v-if="loading && items.length"
      class="item-picker__status is-more"
    >
      正在加载更多工作项…
    </p>
    <p
      v-else-if="problem && items.length"
      class="item-picker__status is-more"
      role="alert"
    >
      加载更多失败<el-button
        text
        size="small"
        @click="load(true)"
      >
        重试
      </el-button>
    </p>
  </div>
</template>

<style scoped>
.item-picker{display:flex;flex-direction:column;min-height:0}
.item-picker__label{display:flex;align-items:center;justify-content:space-between;gap:8px;margin:10px 2px 4px;color:var(--yp-text-muted);font-size:11px}
.item-picker__list{max-height:232px;margin:0;padding:0;overflow:auto;list-style:none;overscroll-behavior:contain}
.item-picker__option{display:flex;align-items:center;gap:8px;min-height:40px;padding:4px 8px;border-radius:var(--yp-radius-sm);cursor:pointer}
.item-picker__option.is-active{background:var(--yp-bg-selected)}
.item-picker__check{flex:none;width:14px;height:14px;box-sizing:border-box;border:1.5px solid var(--yp-border-default);border-radius:3px;background:var(--yp-bg-surface);opacity:0;transition:opacity var(--yp-motion-fast) ease-out}
.item-picker__option:hover .item-picker__check,.item-picker__option.is-active .item-picker__check,.item-picker__option.is-selected .item-picker__check{opacity:1}
.item-picker__option.is-selected .item-picker__check{border-color:var(--yp-action-primary);background:var(--yp-action-primary) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Cpath d='M4 8l3 3 5-6' fill='none' stroke='white' stroke-width='2'/%3E%3C/svg%3E") center/12px no-repeat}
.item-picker__text{display:grid;flex:1;min-width:0;gap:1px}
.item-picker__text strong{overflow:hidden;color:var(--yp-text-primary);font-size:13px;font-weight:500;white-space:nowrap;text-overflow:ellipsis}
.item-picker__text small{overflow:hidden;color:var(--yp-text-muted);font-size:11px;white-space:nowrap;text-overflow:ellipsis}
.item-picker__option.is-completed .item-picker__text{opacity:.5}
.item-picker__option.is-completed .item-picker__text strong{text-decoration:line-through}
.item-picker__done{flex:none;padding:1px 6px;border-radius:var(--yp-radius-pill);background:var(--yp-bg-hover);color:var(--yp-text-muted);font-size:11px;line-height:16px}
.item-picker__status{margin:6px 4px;color:var(--yp-text-muted);font-size:12px;line-height:18px}
.item-picker__status.is-more{text-align:center}
.item-picker-sentinel{height:1px}
</style>
