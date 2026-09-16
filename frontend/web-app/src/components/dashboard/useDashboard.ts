import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { readCsrfToken, type DashboardConfiguration, type DashboardSnapshot, type DashboardSummary, type DashboardView, type DashboardWrite } from '@yumpoo/api-client'
import { dashboardsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { useSession } from '../../composables/useSession'
import { clone, defaultConfiguration } from './dashboardModel'

export function useDashboard() {
  const route = useRoute(), router = useRouter(), session = useSession()
  const dashboards = ref<DashboardSummary[]>([]), dashboard = ref<DashboardView>(), snapshot = ref<DashboardSnapshot>()
  const loading = ref(true), refreshing = ref(false), error = ref(''), saveError = ref(''), conflict = ref(false)
  const name = ref('我的仪表板'), configuration = ref<DashboardConfiguration>(defaultConfiguration())
  const dirty = ref(false), saving = ref(false)
  let epoch = 0, queryEpoch = 0, revision = 0, saveTimer: ReturnType<typeof setTimeout> | undefined
  let pending: { id: string; etag: string; key: string; body: DashboardWrite; revision: number } | undefined
  let activeSave: Promise<boolean> | undefined
  let createAttempt: { signature: string; key: string } | undefined
  let activeLoad: Promise<void> | undefined
  const csrf = () => readCsrfToken() || ''
  const storageKey = computed(() => `yumpoo.dashboard.${session.authentication.value?.company.id}.${session.authentication.value?.user.id}`)
  const saveLabel = computed(() => conflict.value ? '存在更新冲突' : saveError.value ? '保存失败' : saving.value ? '正在保存…' : dirty.value ? '有未保存的更改' : dashboard.value ? '已保存' : '尚未创建')
  async function list() { const token = epoch; const result = await dashboardsApi.listDashboards(); if (token === epoch) dashboards.value = result.items }
  async function load(id?: string) {
    const token = ++epoch; ++queryEpoch; clearTimeout(saveTimer); pending = undefined; dirty.value = false
    loading.value = true; error.value = ''; saveError.value = ''; conflict.value = false; dashboard.value = undefined; snapshot.value = undefined
    try {
      await list(); if (token !== epoch) return
      const remembered = localStorage.getItem(storageKey.value)
      const selected = id || dashboards.value.find(d => d.id === remembered)?.id || dashboards.value[0]?.id
      if (!selected) { name.value = '我的仪表板'; configuration.value = defaultConfiguration(); return }
      if (!id) { await router.replace({ name: 'dashboards', params: { ...route.params, dashboardId: selected } }); return }
      const result = await dashboardsApi.getDashboard({ id: selected }); if (token !== epoch) return
      dashboard.value = result; name.value = result.name; configuration.value = clone(result._configuration)
      // SDK dates must remain Date objects when serialized back to the API.
      restoreDates(); localStorage.setItem(storageKey.value, selected); await refresh()
    } catch (reason) { if (token === epoch) error.value = problemMessage(await toApiProblem(reason)) }
    finally { if (token === epoch) loading.value = false }
  }
  function restoreDates() {
    const f = configuration.value.filters
    if (f.dueFrom) f.dueFrom = new Date(f.dueFrom)
    if (f.dueTo) f.dueTo = new Date(f.dueTo)
  }
  function changed(refreshData = false) {
    ++revision; dirty.value = true; clearTimeout(saveTimer)
    if (dashboard.value && !saveError.value && !conflict.value) saveTimer = setTimeout(() => void save(), 650)
    if (refreshData) void refresh()
  }
  function save(): Promise<boolean> {
    if (activeSave) return activeSave
    activeSave = flushSave().finally(() => { activeSave = undefined })
    return activeSave
  }
  async function flushSave(): Promise<boolean> {
    while (dirty.value && dashboard.value) {
      const succeeded = await saveOnce()
      if (!succeeded) return false
    }
    return true
  }
  async function saveOnce(): Promise<boolean> {
    clearTimeout(saveTimer)
    if (saving.value || conflict.value) return false
    if (!dashboard.value || !dirty.value) return true
    const token = epoch; saving.value = true; saveError.value = ''
    pending ||= { id: dashboard.value.id, etag: dashboard.value.etag, key: crypto.randomUUID(), body: { name: name.value, _configuration: clone(configuration.value) }, revision }
    const request = pending
    for (const field of ['dueFrom', 'dueTo'] as const) if (request.body._configuration.filters[field]) request.body._configuration.filters[field] = new Date(request.body._configuration.filters[field]!)
    try {
      const result = await dashboardsApi.updateDashboard({ id: request.id, ifMatch: request.etag, idempotencyKey: request.key, xXSRFTOKEN: csrf(), dashboardWrite: request.body })
      if (token !== epoch) return false
      dashboard.value = result; pending = undefined; dirty.value = request.revision !== revision
      void list().catch(() => undefined)
      if (request.body._configuration.projectIds.join() !== snapshot.value?.projects.map(p => p.id).join()) await refresh()
      return true
    } catch (reason) {
      if (token === epoch) { const p = await toApiProblem(reason); conflict.value = p.kind === 'response' && p.status === 412; saveError.value = problemMessage(p); if (p.kind === 'response' && [400, 403, 404, 422].includes(p.status)) pending = undefined }
      return false
    } finally {
      saving.value = false
    }
  }
  async function refresh() {
    if (!dashboard.value) return
    const token = ++queryEpoch; refreshing.value = true
    try {
      restoreDates()
      const result = await dashboardsApi.queryDashboard({ id: dashboard.value.id, xXSRFTOKEN: csrf(), dashboardQuery: { filters: configuration.value.filters } })
      if (token === queryEpoch) { snapshot.value = result; error.value = '' }
    } catch (reason) { if (token === queryEpoch) error.value = problemMessage(await toApiProblem(reason)) }
    finally { if (token === queryEpoch) refreshing.value = false }
  }
  async function create(title: string, config: DashboardConfiguration) {
    if (dashboard.value && dirty.value && !conflict.value && !await save()) throw new Error('请先重试保存当前仪表板')
    const token = epoch
    const body = clone(config)
    for (const field of ['dueFrom', 'dueTo'] as const) if (body.filters[field]) body.filters[field] = new Date(body.filters[field]!)
    const signature = JSON.stringify([title, body])
    if (createAttempt?.signature !== signature) createAttempt = { signature, key: crypto.randomUUID() }
    const result = await dashboardsApi.createDashboard({ xXSRFTOKEN: csrf(), idempotencyKey: createAttempt.key, dashboardWrite: { name: title, _configuration: body } })
    if (token !== epoch) return
    createAttempt = undefined
    dirty.value = false
    await router.push({ name: 'dashboards', params: { workspaceSlug: route.params.workspaceSlug, dashboardId: result.id } })
    await activeLoad
  }
  async function remove() {
    if (!dashboard.value || saving.value) return
    await dashboardsApi.deleteDashboard({ id: dashboard.value.id, ifMatch: dashboard.value.etag, xXSRFTOKEN: csrf(), idempotencyKey: crypto.randomUUID() })
    dirty.value = false; await router.replace({ name: 'dashboards', params: { workspaceSlug: route.params.workspaceSlug } })
  }
  async function switchTo(id: string) { if (dirty.value && !await save()) return; await router.push({ name: 'dashboards', params: { ...route.params, dashboardId: id } }) }
  const refreshVisible = () => { if (!document.hidden) void refresh() }
  const interval = setInterval(refreshVisible, 30000)
  window.addEventListener('focus', refreshVisible); window.addEventListener('yumpoo:timer-started', refreshVisible); document.addEventListener('visibilitychange', refreshVisible)
  watch(() => route.params.dashboardId, id => { activeLoad = load(typeof id === 'string' ? id : undefined) }, { immediate: true })
  watch(storageKey, () => { ++epoch; ++queryEpoch; clearTimeout(saveTimer); pending = undefined; createAttempt = undefined; dirty.value = false; name.value = '我的仪表板'; dashboard.value = undefined; snapshot.value = undefined; dashboards.value = []; configuration.value = defaultConfiguration() })
  onBeforeRouteLeave(async () => !dirty.value || await save())
  onBeforeRouteUpdate(async () => !dirty.value || await save())
  const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty.value) event.preventDefault() }
  window.addEventListener('beforeunload', beforeUnload)
  onBeforeUnmount(() => { ++epoch; ++queryEpoch; clearInterval(interval); clearTimeout(saveTimer); window.removeEventListener('focus', refreshVisible); window.removeEventListener('yumpoo:timer-started', refreshVisible); document.removeEventListener('visibilitychange', refreshVisible); window.removeEventListener('beforeunload', beforeUnload) })
  return { dashboards, dashboard, snapshot, loading, refreshing, error, saveError, conflict, name, configuration, dirty, saving, saveLabel, changed, save, refresh, create, remove, switchTo, load }
}
