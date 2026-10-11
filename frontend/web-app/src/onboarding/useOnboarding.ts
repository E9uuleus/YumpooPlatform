import { readCsrfToken } from '@yumpoo/api-client'
import { onScopeDispose, ref } from 'vue'
import { useRouter } from 'vue-router'
import { onboardingApi } from '../api/client'
import { useSession } from '../composables/useSession'
import { TOURS, type TourId } from './tours'

const records = ref<Map<string, number>>()
const active = ref<TourId>()
const welcomeOpen = ref(false)
let replaying = false
let loaded: { userId: string; request: Promise<void> } | undefined

// 每个账号只读取一次；读取失败时本次会话不再自动触发引导。
function load(userId: string): Promise<void> {
  if (loaded?.userId === userId) return loaded.request
  records.value = undefined
  stop()
  const request = onboardingApi.getMyOnboarding().then(state => {
    if (loaded?.request === request) records.value = new Map(state.tours.map(tour => [tour.tourId, tour.version]))
  }, () => undefined)
  loaded = { userId, request }
  return request
}

function handled(id: TourId): boolean {
  return (records.value?.get(id) ?? 0) >= TOURS[id].version
}

/** 页面数据就绪时调用；页面引导要等欢迎引导处理后才会自动出现。 */
async function ready(id: TourId, isCurrentPage: () => boolean): Promise<void> {
  const userId = useSession().authentication.value?.user.id
  if (!userId || !isCurrentPage()) return
  await load(userId)
  if (!isCurrentPage() || useSession().authentication.value?.user.id !== userId) return
  if (!records.value || active.value || welcomeOpen.value || handled(id)) return
  if (id === 'welcome') welcomeOpen.value = true
  else if (handled('welcome')) active.value = id
}

function start(id: TourId): void {
  replaying = false
  welcomeOpen.value = false
  active.value = id
}

function replay(id: TourId): void {
  start(id)
  replaying = true
}

function stop(): void {
  active.value = undefined
  welcomeOpen.value = false
  replaying = false
}

/** 完成、跳过和关闭都视为已处理。 */
function complete(id: TourId): void {
  if (active.value !== id && !(id === 'welcome' && welcomeOpen.value)) return
  const shouldRecord = !replaying
  stop()
  if (!shouldRecord || handled(id)) return
  const version = TOURS[id].version
  records.value?.set(id, version)
  const csrf = readCsrfToken()
  if (!csrf) return
  void onboardingApi.completeMyOnboardingTour({ tourId: id, xXSRFTOKEN: csrf, onboardingTourCompleteRequest: { version } })
    .catch(() => undefined)
}

export function useOnboarding() {
  return { active, welcomeOpen, start, replay, stop, complete }
}

/** 在页面 setup 中绑定生命周期，数据加载后的回调不能启动已离开页面的引导。 */
export function usePageOnboarding() {
  const router = useRouter()
  const id = router?.currentRoute?.value.meta.onboardingTour
  let disposed = false
  onScopeDispose(() => { disposed = true })
  return {
    ready: async () => {
      const route = router?.currentRoute?.value
      if (!id || route?.meta.onboardingTour !== id) return
      await ready(id, () => !disposed && router.currentRoute.value === route)
    },
  }
}

export function resetOnboardingForTests(): void {
  records.value = undefined
  stop()
  loaded = undefined
}
