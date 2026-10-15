<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, toRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton } from 'element-plus'
import DayHeader from './DayHeader.vue'
import DayTimeline from './DayTimeline.vue'
import ItemShelf from './ItemShelf.vue'
import { addDays } from '../../team/teamDashboardModel'
import { useDayTimeline } from './useDayTimeline'

const props = defineProps<{ date: string; today: string; timezone: string }>()
const route = useRoute()
const router = useRouter()
const { page, day: calendarDay, blocks, layoutMap, shareMap, totals, recordedMs, targetMs, dayRange, workday, now, loading, problem, fresh, load }
  = useDayTimeline(toRef(props, 'date'), toRef(props, 'timezone'))
const timeline = ref<InstanceType<typeof DayTimeline>>()

function navigate(date: string) {
  if (date > props.today) return
  void router.replace({ name: 'my-time-day', params: { ...route.params, date } })
}
function back() {
  void router.push({ name: 'my-time', params: { workspaceSlug: route.params.workspaceSlug }, query: { month: props.date.slice(0, 7) } })
}

function shortcuts(event: KeyboardEvent) {
  if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
  const target = event.target as HTMLElement | null
  if (target?.closest('input, textarea, select, [contenteditable="true"], [role="dialog"], .el-popper')) return
  if (event.key === 'ArrowLeft') navigate(addDays(props.date, -1))
  else if (event.key === 'ArrowRight') navigate(addDays(props.date, 1))
  else if (event.key === 't' || event.key === 'T') navigate(props.today)
  else return
  event.preventDefault()
}
onMounted(() => document.addEventListener('keydown', shortcuts))
onBeforeUnmount(() => document.removeEventListener('keydown', shortcuts))
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
        :timezone="timezone"
        :fresh="fresh"
      />
    </div>
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
