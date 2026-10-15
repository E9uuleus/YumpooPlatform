<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, toRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElPopover } from 'element-plus'
import type { TimeSessionFillMode, TimerCandidate } from '@yumpoo/api-client'
import { useTimeTracker } from '../../../composables/useTimeTracker'
import { addDays } from '../../team/teamDashboardModel'
import BlockPopover from './BlockPopover.vue'
import DayHeader from './DayHeader.vue'
import DayTimeline from './DayTimeline.vue'
import EntryComposer from './EntryComposer.vue'
import ItemShelf from './ItemShelf.vue'
import type { DayBlock, Range } from './dayModel'
import { useDayTimeline } from './useDayTimeline'

const props = defineProps<{ date: string; today: string; timezone: string }>()
const route = useRoute()
const router = useRouter()
const tracker = useTimeTracker()
const { page, day: calendarDay, blocks, layoutMap, shareMap, totals, recordedMs, targetMs, dayRange, workday, now, limit, loading, busy,
  problem, fresh, preferences, load, create, edit, remove, reassign, exclusive } = useDayTimeline(toRef(props, 'date'), toRef(props, 'timezone'))
const timeline = ref<InstanceType<typeof DayTimeline>>()

type PopoverInstance = InstanceType<typeof ElPopover> & { popperRef?: { popperInstanceRef?: { update?: () => void } } }
const composerPopover = ref<PopoverInstance>()
const pending = ref<Range | null>(null)
const pendingAnchor = shallowRef<HTMLElement>()
const openId = ref<string>()
const blockAnchor = shallowRef<HTMLElement>()
const openBlock = computed(() => blocks.value.find(block => block.id === openId.value))

async function compose(range: Range) {
  closeBlock()
  pending.value = range
  await nextTick()
  pendingAnchor.value = timeline.value?.pendingEl ?? undefined
}
async function moveComposer(range: Range) {
  pending.value = range
  await nextTick()
  composerPopover.value?.popperRef?.popperInstanceRef?.update?.()
}
function closeComposer() { pending.value = null; pendingAnchor.value = undefined }
function openBlockPopover(block: DayBlock, anchor: HTMLElement) {
  closeComposer()
  openId.value = block.id
  blockAnchor.value = anchor
}
function closeBlock() { openId.value = undefined; blockAnchor.value = undefined }
watch(openBlock, block => { if (openId.value && !block) closeBlock() })
watch(() => props.date, () => { closeComposer(); closeBlock() })

async function submit(mode: TimeSessionFillMode, items: TimerCandidate[], range: Range) {
  if (await create(mode, items.map(item => item.workItemId), [range])) closeComposer()
}
async function act(action: (block: DayBlock) => Promise<boolean>) {
  const block = openBlock.value
  if (block && await action(block)) closeBlock()
}
async function stopTimer() {
  const block = openBlock.value
  if (block && await tracker.stop(block.id)) closeBlock()
}

function navigate(date: string) {
  if (date > props.today) return
  void router.replace({ name: 'my-time-day', params: { ...route.params, date } })
}
function back() {
  void router.push({ name: 'my-time', params: { workspaceSlug: route.params.workspaceSlug }, query: { month: props.date.slice(0, 7) } })
}

function closeOnOutside(event: PointerEvent) {
  const target = event.target
  if (!(target instanceof Element) || target.closest('.day-popover')) return
  if (pending.value) closeComposer()
  if (openId.value && !blockAnchor.value?.contains(target)) closeBlock()
}
function shortcuts(event: KeyboardEvent) {
  if (event.key === 'Escape' && (pending.value || openId.value)) { closeComposer(); closeBlock(); return }
  if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || pending.value || openId.value) return
  const target = event.target as HTMLElement | null
  if (target?.closest('input, textarea, select, [contenteditable="true"], [role="dialog"], .el-popper')) return
  if (event.key === 'ArrowLeft') navigate(addDays(props.date, -1))
  else if (event.key === 'ArrowRight') navigate(addDays(props.date, 1))
  else if (event.key === 't' || event.key === 'T') navigate(props.today)
  else return
  event.preventDefault()
}
onMounted(() => {
  document.addEventListener('keydown', shortcuts)
  document.addEventListener('pointerdown', closeOnOutside, true)
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', shortcuts)
  document.removeEventListener('pointerdown', closeOnOutside, true)
})
</script>

<template>
  <section
    class="my-day"
    aria-label="我的一天"
  >
    <DayHeader
      :date="date"
      :today="today"
      :day="calendarDay"
      :recorded-ms="recordedMs"
      :target-ms="targetMs"
      @navigate="navigate"
      @back="back"
    />
    <el-alert
      v-if="problem"
      class="my-day__alert"
      :title="problem"
      type="error"
      :closable="false"
    >
      <el-button
        text
        @click="load()"
      >
        重试
      </el-button>
    </el-alert>
    <div
      v-loading="loading && !page"
      class="my-day__body"
    >
      <ItemShelf :totals="totals" />
      <DayTimeline
        ref="timeline"
        :blocks="blocks"
        :layout="layoutMap"
        :shares="shareMap"
        :bounds="dayRange"
        :workday="workday"
        :now="now"
        :limit="limit"
        :timezone="timezone"
        :fresh="fresh"
        :pending="pending"
        :selected-id="openId"
        @create="compose"
        @open="openBlockPopover"
      />
    </div>
    <el-popover
      v-if="pending && pendingAnchor"
      ref="composerPopover"
      :visible="true"
      virtual-triggering
      :virtual-ref="pendingAnchor"
      placement="left-start"
      :width="380"
      :persistent="false"
      popper-class="day-popover"
    >
      <EntryComposer
        :range="pending"
        :blocks="blocks"
        :bounds="dayRange"
        :limit="limit"
        :date="date"
        :timezone="timezone"
        :busy="busy"
        @update:range="moveComposer"
        @submit="submit"
        @close="closeComposer"
      />
    </el-popover>
    <el-popover
      v-if="openBlock && blockAnchor"
      :key="openBlock.id"
      :visible="true"
      virtual-triggering
      :virtual-ref="blockAnchor"
      placement="left-start"
      :width="340"
      :persistent="false"
      popper-class="day-popover"
    >
      <BlockPopover
        :block="openBlock"
        :share="shareMap.get(openBlock.id) ?? { allocatedMs: 0, maxParallel: 1 }"
        :bounds="dayRange"
        :limit="limit"
        :date="date"
        :timezone="timezone"
        :timer-enabled="preferences.timerEnabled"
        :busy="busy"
        @save="range => act(block => edit(block, range))"
        @remove="act(remove)"
        @reassign="workItemId => act(block => reassign(block, workItemId))"
        @exclusive="act(exclusive)"
        @stop="stopTimer"
        @close="closeBlock"
      />
    </el-popover>
  </section>
</template>

<style scoped>
.my-day{display:flex;flex-direction:column;flex:1;min-height:0;background:var(--yp-bg-sunken);animation:my-day-in 240ms var(--yp-ease-standard) backwards}
.my-day__alert{margin:12px 16px 0;width:auto}
.my-day__body{display:grid;grid-template-columns:280px minmax(0,1fr);gap:12px;flex:1;min-height:0;padding:12px 16px 16px}
@keyframes my-day-in{from{opacity:0;transform:translateY(6px)}}
@media (max-width:900px){.my-day__body{grid-template-columns:minmax(0,1fr);grid-template-rows:auto minmax(420px,1fr);overflow:auto}}
@media (prefers-reduced-motion:reduce){.my-day{animation:none}}
</style>

<style>
.day-popover.el-popover.el-popper{padding:14px;border-radius:var(--yp-radius-md);box-shadow:var(--yp-shadow-popover)}
</style>
