import { readCsrfToken, type WorkItemCreateDefaultValues, type WorkItemTableSettings } from '@yumpoo/api-client'
import { ElMessage } from 'element-plus'
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { emptyTableSettings, type TableSettings } from './workItemTableSettings'

const SAVE_DELAY_MS = 400
const RETRY_DELAYS_MS = [1_000, 3_000]
type SettingsPatch = Partial<Omit<TableSettings, 'defaultValues'>> & { defaultValues?: Partial<WorkItemCreateDefaultValues> }
export type TableSettingsSaveState = 'saved' | 'pending' | 'saving' | 'error'

interface SaveContext {
  projectId: string
  confirmed: WorkItemTableSettings
  pending: SettingsPatch
  activePatch?: SettingsPatch | undefined
  saving: boolean
  failures: number
  conflicted?: boolean
  error?: string | undefined
  timer?: ReturnType<typeof setTimeout> | undefined
}

function equal(left: unknown, right: unknown): boolean {
  const encode = (value: unknown) => JSON.stringify(value, (_key, item: unknown) => item instanceof Set ? [...item] : item)
  return encode(left) === encode(right)
}

function changedFields(before: TableSettings, patch: Partial<TableSettings>): SettingsPatch {
  const result = Object.fromEntries(Object.entries(patch).filter(([key, value]) =>
    key !== 'defaultValues' && !equal(before[key as keyof TableSettings], value))) as SettingsPatch
  if (patch.defaultValues) {
    const defaults = Object.fromEntries(Object.entries(patch.defaultValues).filter(([key, value]) =>
      !equal(before.defaultValues[key as keyof WorkItemCreateDefaultValues], value)))
    if (Object.keys(defaults).length) result.defaultValues = defaults
  }
  return result
}

function merge(earlier: SettingsPatch, later: SettingsPatch): SettingsPatch {
  return { ...earlier, ...later, ...(earlier.defaultValues || later.defaultValues
    ? { defaultValues: { ...earlier.defaultValues, ...later.defaultValues } } : {}) }
}

function apply(base: TableSettings, patch: SettingsPatch): TableSettings {
  return {
    pinnedColumnCount: base.pinnedColumnCount, headerHeight: base.headerHeight, rowHeight: base.rowHeight,
    coloringRules: base.coloringRules, ...patch, defaultValues: { ...base.defaultValues, ...patch.defaultValues },
  }
}

function overlaps(base: TableSettings, latest: TableSettings, patch: SettingsPatch): boolean {
  return Object.entries(patch).some(([key, value]) => key === 'defaultValues'
    ? Object.entries(patch.defaultValues!).some(([field, desired]) => {
      const name = field as keyof WorkItemCreateDefaultValues
      return !equal(base.defaultValues[name], latest.defaultValues[name]) && !equal(latest.defaultValues[name], desired)
    })
    : !equal(base[key as keyof TableSettings], latest[key as keyof TableSettings]) && !equal(latest[key as keyof TableSettings], value))
}

/** 只合并本地改动；If-Match 冲突后自动合并互不重叠的字段，相同字段冲突由用户重试确认。 */
export function useWorkItemTableSettings(projectId: () => string) {
  const settings = shallowRef<TableSettings>(emptyTableSettings())
  const state = ref<'loading' | 'ready' | 'error'>('loading')
  const saveState = ref<TableSettingsSaveState>('saved')
  const saveError = ref<string>()
  let revision = 0
  let current: SaveContext | undefined

  function publish(context: SaveContext): void {
    if (current !== context) return
    const next = apply(context.confirmed, merge(context.activePatch ?? {}, context.pending))
    if (!equal(settings.value, next)) settings.value = next
    saveError.value = context.error
    saveState.value = context.error ? 'error' : context.saving ? 'saving'
      : Object.keys(context.pending).length ? 'pending' : 'saved'
  }

  async function load(): Promise<void> {
    if (current) void flush(current)
    current = undefined
    const requestedRevision = ++revision, requestedProject = projectId()
    state.value = 'loading'
    settings.value = emptyTableSettings()
    saveState.value = 'saved'
    saveError.value = undefined
    try {
      const loaded = await workItemsApi.getMyWorkItemTableSettings({ projectId: requestedProject })
      if (requestedRevision !== revision) return
      current = { projectId: requestedProject, confirmed: loaded, pending: {}, saving: false, failures: 0 }
      publish(current)
      state.value = 'ready'
    } catch {
      if (requestedRevision === revision) state.value = 'error'
    }
  }

  function schedule(context: SaveContext, delay: number): void {
    if (context.timer) clearTimeout(context.timer)
    context.timer = setTimeout(() => void flush(context), delay)
  }

  function update(patch: Partial<TableSettings>): void {
    if (state.value !== 'ready' || !current) return
    current.pending = merge(current.pending, changedFields(settings.value, patch))
    if (!current.conflicted) current.error = undefined
    current.failures = 0
    publish(current)
    if (!current.conflicted) schedule(current, SAVE_DELAY_MS)
  }

  async function flush(context: SaveContext): Promise<void> {
    if (context.timer) clearTimeout(context.timer)
    context.timer = undefined
    if (context.saving || context.conflicted || !Object.keys(context.pending).length) return
    const patch = context.pending
    context.pending = {}
    context.activePatch = patch
    context.saving = true
    context.error = undefined
    publish(context)
    let failureMessage: string | undefined
    try {
      const csrf = readCsrfToken()
      if (!csrf) {
        failureMessage = '缺少 CSRF 凭据，表格设置尚未保存，请刷新登录后重试。'
        throw new Error(failureMessage)
      }
      let base = context.confirmed
      for (let attempt = 0; ; attempt++) {
        try {
          context.confirmed = await workItemsApi.updateMyWorkItemTableSettings({
            projectId: context.projectId, xXSRFTOKEN: csrf, ifMatch: base.etag,
            workItemTableSettingsUpdateRequest: apply(base, patch),
          })
          break
        } catch (reason) {
          const problem = await toApiProblem(reason)
          if (problem.kind !== 'response' || problem.status !== 412 || attempt >= 2) throw reason
          const latest = await workItemsApi.getMyWorkItemTableSettings({ projectId: context.projectId })
          context.confirmed = latest
          if (overlaps(base, latest, patch)) {
            context.conflicted = true
            failureMessage = '同一设置已在其他窗口修改，尚未保存。重试保存将使用本地修改。'
            throw reason
          }
          base = latest
        }
      }
      context.failures = 0
    } catch (reason) {
      context.pending = merge(patch, context.pending)
      const problem = await toApiProblem(reason)
      context.error = failureMessage ?? '表格设置尚未保存：' + problemMessage(problem)
      if (context.failures++ === 0) ElMessage.error(context.error)
      const delay = RETRY_DELAYS_MS[context.failures - 1]
      if (!failureMessage && delay !== undefined && (problem.kind === 'fallback'
        || problem.status === 408 || problem.status === 429 || problem.status >= 500)) schedule(context, delay)
    } finally {
      context.activePatch = undefined
      context.saving = false
      publish(context)
      if (!context.error && !context.timer && Object.keys(context.pending).length) void flush(context)
    }
  }

  function retrySave(): void {
    if (!current) return
    current.failures = 0
    current.conflicted = false
    void flush(current)
  }

  watch(projectId, () => void load(), { immediate: true })
  onBeforeUnmount(() => { revision++; if (current) void flush(current) })
  return { settings, state, saveState, saveError, load, update, retrySave }
}
