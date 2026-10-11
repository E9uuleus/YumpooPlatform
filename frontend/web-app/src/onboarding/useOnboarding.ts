import { readCsrfToken } from '@yumpoo/api-client'
import { ref } from 'vue'
import { onboardingApi } from '../api/client'
import { useSession } from '../composables/useSession'
import { TOURS, type TourId } from './tours'

const records = ref<Map<string, number>>()
const active = ref<TourId>()
const welcomeOpen = ref(false)
let loaded: { userId: string; request: Promise<void> } | undefined

// 每个账号只读取一次；读取失败时本次会话不再自动触发引导。
function load(userId: string): Promise<void> {
  if (loaded?.userId === userId) return loaded.request
  records.value = undefined
  active.value = undefined
  welcomeOpen.value = false
  const request = onboardingApi.getMyOnboarding().then(state => {
    if (loaded?.userId === userId) records.value = new Map(state.tours.map(tour => [tour.tourId, tour.version]))
  }, () => undefined)
  loaded = { userId, request }
  return request
}

function handled(id: TourId): boolean {
  return (records.value?.get(id) ?? 0) >= TOURS[id].version
}

/** 页面数据就绪时调用；页面引导要等欢迎引导处理后才会自动出现。 */
async function ready(id: TourId): Promise<void> {
  const userId = useSession().authentication.value?.user.id
  if (!userId) return
  await load(userId)
  if (!records.value || active.value || welcomeOpen.value || handled(id)) return
  if (id === 'welcome') welcomeOpen.value = true
  else if (handled('welcome')) active.value = id
}

function start(id: TourId): void {
  welcomeOpen.value = false
  active.value = id
}

function stop(): void {
  active.value = undefined
}

/** 完成、跳过和关闭都视为已处理。 */
function complete(id: TourId): void {
  active.value = undefined
  welcomeOpen.value = false
  if (handled(id)) return
  const version = TOURS[id].version
  records.value?.set(id, version)
  const csrf = readCsrfToken()
  if (!csrf) return
  void onboardingApi.completeMyOnboardingTour({ tourId: id, xXSRFTOKEN: csrf, onboardingTourCompleteRequest: { version } })
    .catch(() => undefined)
}

export function useOnboarding() {
  return { active, welcomeOpen, ready, start, stop, complete }
}

export function resetOnboardingForTests(): void {
  records.value = undefined
  active.value = undefined
  welcomeOpen.value = false
  loaded = undefined
}
