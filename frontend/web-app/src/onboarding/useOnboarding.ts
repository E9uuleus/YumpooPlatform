import { readCsrfToken } from '@yumpoo/api-client'
import { onScopeDispose, ref } from 'vue'
import { useRouter } from 'vue-router'
import { onboardingApi } from '../api/client'
import { useSession } from '../composables/useSession'
import { TOURS, type TourId } from './tours'

const records = ref<Map<string, number>>()
/** `update` 只展示 `since` 晚于 `from`（用户已处理的版本）的步骤。 */
const active = ref<{ id: TourId; mode: 'automatic' | 'replay' } | { id: TourId; mode: 'update'; from: number }>()
const welcomeOpen = ref(false)
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

/** 页面数据就绪时调用；页面引导要等欢迎引导处理后才会自动出现，已处理旧版本的引导只展示新增步骤。 */
async function ready(id: TourId, isCurrentPage: () => boolean): Promise<void> {
  const userId = useSession().authentication.value?.user.id
  if (!userId || !isCurrentPage()) return
  await load(userId)
  if (!isCurrentPage() || useSession().authentication.value?.user.id !== userId) return
  if (!records.value || active.value || welcomeOpen.value || handled(id)) return
  const from = records.value.get(id) ?? 0
  if (!from) {
    if (id === 'welcome') welcomeOpen.value = true
    else if (handled('welcome')) start(id)
  }
  else if (TOURS[id].steps.some(step => (step.since ?? 1) > from)) active.value = { id, mode: 'update', from }
  else record(id)
}

function start(id: TourId): void {
  welcomeOpen.value = false
  active.value = { id, mode: 'automatic' }
}

function replay(id: TourId): void {
  welcomeOpen.value = false
  active.value = { id, mode: 'replay' }
}

function stop(): void {
  active.value = undefined
  welcomeOpen.value = false
}

/** 自动引导的完成、跳过和关闭均记为已处理；重放只关闭本地导览。 */
function complete(id: TourId): void {
  const tour = active.value
  if (tour?.id !== id && !(id === 'welcome' && welcomeOpen.value)) return
  stop()
  if (tour?.mode !== 'replay') record(id)
}

function record(id: TourId): void {
  if (handled(id)) return
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
export function usePageOnboarding(isReady: () => boolean = () => true) {
  const router = useRouter()
  const id = router?.currentRoute?.value.meta.onboardingTour
  let disposed = false
  onScopeDispose(() => { disposed = true })
  return {
    ready: async () => {
      const route = router?.currentRoute?.value
      if (!id || route?.meta.onboardingTour !== id) return
      await ready(id, () => !disposed && router.currentRoute.value === route && isReady())
    },
  }
}

export function resetOnboardingForTests(): void {
  records.value = undefined
  stop()
  loaded = undefined
}
