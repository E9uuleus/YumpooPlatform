<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MyDayView from '../components/my-time/day/MyDayView.vue'
import { todayIn } from '../components/team/teamDashboardModel'
import { useSession } from '../composables/useSession'
import { useTimeTracker } from '../composables/useTimeTracker'

const route = useRoute()
const router = useRouter()
const session = useSession()
const tracker = useTimeTracker()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const today = computed(() => todayIn(timezone.value, new Date(tracker.now.value)))
const date = computed(() => String(route.params.date))
watch(date, value => {
  if (value > today.value) void router.replace({ name: 'my-time-day', params: { ...route.params, date: today.value } })
}, { immediate: true })
</script>

<template>
  <section class="dashboard-page">
    <MyDayView
      :date="date > today ? today : date"
      :today="today"
      :timezone="timezone"
    />
  </section>
</template>

<style src="../components/dashboard/dashboard.css" />
