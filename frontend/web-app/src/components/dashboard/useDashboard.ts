import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { readCsrfToken, type DashboardConfiguration, type DashboardSnapshot, type DashboardSummary, type DashboardView, type DashboardWrite } from '@yumpoo/api-client'
import { dashboardsApi } from '../../api/client'
import { problemMessage, toApiProblem, type ApiProblem } from '../../api/problems'
import { useSession } from '../../composables/useSession'
import { clone, defaultConfiguration } from './dashboardModel'
import { settleDashboardLayout } from './dashboardLayout'

export function useDashboard() {
  const route = useRoute(), router = useRouter(), session = useSession()
  const dashboards = ref<DashboardSummary[]>([]), dashboard = ref<DashboardView>(), snapshot = ref<DashboardSnapshot>()
  const loading = ref(true), refreshing = ref(false), error = ref(''), saveError = ref('')
  const name = ref('我的仪表板'), configuration = ref<DashboardConfiguration>(defaultConfiguration())
  const dirty = ref(false), saving = ref(false)
  const recovery = ref<'leave' | 'reload'>(), reloading = ref(false)
  let recoverableValidation = false, leaving: Promise<boolean> | undefined
  let recoveryResult: ((choice: 'saved' | 'discard' | 'stay') => void) | undefined
  let epoch = 0, queryEpoch = 0, revision = 0, saveTimer: ReturnType<typeof setTimeout> | undefined
  let queryTimer: ReturnType<typeof setTimeout> | undefined
  let pending: { id: string; etag: string; key: string; body: DashboardWrite; revision: number } | undefined
  let activeSave: Promise<boolean> | undefined
  let createAttempt: { signature: string; key: string } | undefined
  let activeLoad: Promise<void> | undefined
  const csrf = () => readCsrfToken() || ''
  const storageKey = computed(() => `yumpoo.dashboard.${session.authentication.value?.company.id}.${session.authentication.value?.user.id}`)
  function message(p: ApiProblem) {
    return p.kind === 'response' && p.error?.fieldErrors?.length
      ? [...new Set(p.error.fieldErrors.map(field => field.message))].join('；') : problemMessage(p)
  }
  function clearDraft() {
    clearTimeout(saveTimer); clearTimeout(queryTimer); ++revision; ++queryEpoch
    pending = undefined; refreshing.value = false; dirty.value = false; saveError.value = ''; recoverableValidation = false
    if (dashboard.value) { name.value = dashboard.value.name; configuration.value = clone(dashboard.value._configuration); restoreDates() }
  }
  function askRecovery(action: 'leave' | 'reload') {
    recovery.value = action
    return new Promise<'saved' | 'discard' | 'stay'>(resolve => { recoveryResult = resolve })
  }
  async function resolveRecovery(choice: 'retry' | 'discard' | 'stay') {
    if (saving.value) return
    if (choice === 'retry' && !await save()) return
    const resolve = recoveryResult; recoveryResult = undefined; recovery.value = undefined
    resolve?.(choice === 'retry' ? 'saved' : choice)
  }
  function canLeave(): Promise<boolean> {
    if (leaving) return leaving
    if (recovery.value) return Promise.resolve(false)
    leaving = (async () => {
      if (!dirty.value || await save()) return true
      const choice = await askRecovery('leave')
      if (choice === 'discard') clearDraft()
      return choice !== 'stay'
    })().finally(() => { leaving = undefined })
    return leaving
  }
  async function list() { const token = epoch; const result = await dashboardsApi.listDashboards(); if (token === epoch) dashboards.value = result.items }
  async function load(id?: string) {
    const token = ++epoch; ++queryEpoch; clearTimeout(saveTimer); clearTimeout(queryTimer); pending = undefined; dirty.value = false
    loading.value = true; refreshing.value = false; error.value = ''; saveError.value = ''; recoverableValidation = false; dashboard.value = undefined; snapshot.value = undefined
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
  function restoreDates(config = configuration.value) {
    for (const f of [config.filters, ...config.widgets.map(w => w.chart?.filters)]) {
      if (!f) continue
      if (f.dueFrom) f.dueFrom = new Date(f.dueFrom)
      if (f.dueTo) f.dueTo = new Date(f.dueTo)
    }
  }
  function changed(refreshData = false, compact = false) {
    configuration.value.widgets = settleDashboardLayout(configuration.value.widgets, compact)
    ++revision; dirty.value = true; clearTimeout(saveTimer)
    if (recoverableValidation) { saveError.value = ''; recoverableValidation = false }
    if (dashboard.value && !saveError.value) saveTimer = setTimeout(() => void save(), 650)
    if (refreshData) { ++queryEpoch; clearTimeout(queryTimer); queryTimer = setTimeout(() => void refresh(), 250) }
  }
  function save(): Promise<boolean> {
    if (activeSave) return activeSave
    activeSave = flushSave().finally(() => {
      activeSave = undefined
      if (dirty.value && dashboard.value && !saveError.value) { clearTimeout(saveTimer); saveTimer = setTimeout(() => void save(), 650) }
    })
    return activeSave
  }
  async function flushSave(): Promise<boolean> {
    const generation = epoch
    let conflicts = 0
    while (generation === epoch && dirty.value && dashboard.value) {
      const succeeded = await saveOnce(() => ++conflicts <= 3)
      if (!succeeded) return false
    }
    return true
  }
  async function saveOnce(canRetryConflict: () => boolean): Promise<boolean> {
    clearTimeout(saveTimer)
    if (saving.value) return false
    if (!dashboard.value || !dirty.value) return true
    const token = epoch; saving.value = true; saveError.value = ''
    pending ||= { id: dashboard.value.id, etag: dashboard.value.etag, key: crypto.randomUUID(), body: { name: name.value, _configuration: clone(configuration.value) }, revision }
    const request = pending
    restoreDates(request.body._configuration)
    try {
      const result = await dashboardsApi.updateDashboard({ id: request.id, ifMatch: request.etag, idempotencyKey: request.key, xXSRFTOKEN: csrf(), dashboardWrite: request.body })
      if (token !== epoch) return false
      dashboard.value = result; pending = undefined; dirty.value = request.revision !== revision
      void list().catch(() => undefined)
      if (request.body._configuration.projectIds.join() !== snapshot.value?.projects.map(p => p.id).join()) await refresh()
      return true
    } catch (reason) {
      let p = await toApiProblem(reason)
      if (token === epoch && p.kind === 'response' && p.status === 412) {
        // A rejected version has not written anything. Rebase only the ETag; keep this window's draft.
        pending = undefined
        if (canRetryConflict()) {
          try {
            const latest = await dashboardsApi.getDashboard({ id: request.id })
            if (token !== epoch) return false
            dashboard.value = { ...dashboard.value!, etag: latest.etag, version: latest.version }
            return true
          } catch (reason) { p = await toApiProblem(reason) }
        } else {
          // Let competing windows finish before another automatic attempt.
          return false
        }
      }
      if (token === epoch) {
        saveError.value = message(p)
        recoverableValidation = p.kind === 'response' && [400, 422].includes(p.status)
        if (p.kind === 'response' && [400, 403, 404, 422].includes(p.status)) pending = undefined
        if (recoverableValidation && revision !== request.revision) { recoverableValidation = false; saveError.value = ''; saveTimer = setTimeout(() => void save(), 650) }
      }
      return false
    } finally {
      saving.value = false
    }
  }
  async function refresh() {
    if (!dashboard.value) return
    clearTimeout(queryTimer)
    const token = ++queryEpoch; refreshing.value = true
    try {
      restoreDates()
      const result = await dashboardsApi.queryDashboard({ id: dashboard.value.id, xXSRFTOKEN: csrf(), dashboardQuery: { filters: configuration.value.filters, widgets: configuration.value.widgets } })
      if (token === queryEpoch) { snapshot.value = result; error.value = '' }
    } catch (reason) { const p = await toApiProblem(reason); if (token === queryEpoch) error.value = message(p) }
    finally { if (token === queryEpoch) refreshing.value = false }
  }
  async function reload(discard = false) {
    if (!dashboard.value || reloading.value || recovery.value) return
    if (dirty.value && !discard && !await save()) {
      const choice = await askRecovery('reload')
      if (choice === 'stay') return
    }
    if (activeSave) await activeSave
    const id = dashboard.value.id, generation = epoch, draftRevision = revision
    const token = ++queryEpoch; clearTimeout(saveTimer); clearTimeout(queryTimer); reloading.value = true
    try {
      const result = await dashboardsApi.getDashboard({ id })
      const config = clone(result._configuration); restoreDates(config)
      const data = await dashboardsApi.queryDashboard({ id, xXSRFTOKEN: csrf(), dashboardQuery: { filters: config.filters, widgets: config.widgets } })
      if (generation !== epoch || token !== queryEpoch || draftRevision !== revision) return
      dashboard.value = result; clearDraft(); snapshot.value = data; error.value = ''
      void list().catch(() => undefined)
    } catch (reason) { const p = await toApiProblem(reason); if (generation === epoch && token === queryEpoch) error.value = message(p) }
    finally { reloading.value = false }
  }
  async function create(title: string, config: DashboardConfiguration, copyDraft = false) {
    if (dashboard.value && dirty.value && !copyDraft && !await save()) throw new Error('请先重试保存当前仪表板')
    const token = epoch
    const body = clone(config)
    restoreDates(body)
    const signature = JSON.stringify([title, body])
    if (createAttempt?.signature !== signature) createAttempt = { signature, key: crypto.randomUUID() }
    const result = await dashboardsApi.createDashboard({ xXSRFTOKEN: csrf(), idempotencyKey: createAttempt.key, dashboardWrite: { name: title, _configuration: body } })
    if (token !== epoch) return
    createAttempt = undefined
    clearTimeout(saveTimer); clearTimeout(queryTimer); pending = undefined; dirty.value = false
    await router.push({ name: 'dashboards', params: { workspaceSlug: route.params.workspaceSlug, dashboardId: result.id } })
    await activeLoad
  }
  async function remove() {
    if (!dashboard.value || saving.value) return
    await dashboardsApi.deleteDashboard({ id: dashboard.value.id, ifMatch: dashboard.value.etag, xXSRFTOKEN: csrf(), idempotencyKey: crypto.randomUUID() })
    dirty.value = false; await router.replace({ name: 'dashboards', params: { workspaceSlug: route.params.workspaceSlug } })
  }
  async function switchTo(id: string) { if (!await canLeave()) return; await router.push({ name: 'dashboards', params: { ...route.params, dashboardId: id } }) }
  watch(() => route.params.dashboardId, id => { activeLoad = load(typeof id === 'string' ? id : undefined) }, { immediate: true })
  watch(storageKey, () => { ++epoch; ++queryEpoch; clearTimeout(saveTimer); clearTimeout(queryTimer); pending = undefined; createAttempt = undefined; dirty.value = false; name.value = '我的仪表板'; dashboard.value = undefined; snapshot.value = undefined; dashboards.value = []; configuration.value = defaultConfiguration() })
  onBeforeRouteLeave(canLeave)
  onBeforeRouteUpdate((to, from) => to.params.dashboardId !== from.params.dashboardId ? canLeave() : true)
  const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty.value) event.preventDefault() }
  window.addEventListener('beforeunload', beforeUnload)
  onBeforeUnmount(() => { ++epoch; ++queryEpoch; recoveryResult?.('stay'); clearTimeout(saveTimer); clearTimeout(queryTimer); window.removeEventListener('beforeunload', beforeUnload) })
  return { dashboards, dashboard, snapshot, loading, refreshing, error, saveError, name, configuration, dirty, saving, changed, save, refresh, reload, reloading, recovery, resolveRecovery, create, remove, switchTo, load }
}
