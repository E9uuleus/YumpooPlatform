import { readCsrfToken } from '@yumpoo/api-client'
import { ElMessage } from 'element-plus'
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { emptyTableSettings, type TableSettings } from './workItemTableSettings'

const SAVE_DELAY_MS = 400

/** 本人在项目中的表格设置：修改立即生效，停顿后整体 PUT；保存按调用顺序串行，失败只提示并保留本地状态。 */
export function useWorkItemTableSettings(projectId: () => string) {
  const settings = shallowRef<TableSettings>(emptyTableSettings())
  const state = ref<'loading' | 'ready' | 'error'>('loading')
  let revision = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let pendingProjectId: string | undefined
  let queue: Promise<void> = Promise.resolve()

  async function load(): Promise<void> {
    flush()
    const current = ++revision
    const requested = projectId()
    state.value = 'loading'
    settings.value = emptyTableSettings()
    try {
      const loaded = await workItemsApi.getMyWorkItemTableSettings({ projectId: requested })
      if (current !== revision) return
      settings.value = {
        pinnedColumnCount: loaded.pinnedColumnCount, headerHeight: loaded.headerHeight, rowHeight: loaded.rowHeight,
        coloringRules: loaded.coloringRules, defaultValues: loaded.defaultValues,
      }
      state.value = 'ready'
    } catch {
      if (current === revision) state.value = 'error'
    }
  }

  function update(patch: Partial<TableSettings>): void {
    if (state.value !== 'ready') return
    settings.value = { ...settings.value, ...patch }
    pendingProjectId = projectId()
    if (timer) clearTimeout(timer)
    timer = setTimeout(flush, SAVE_DELAY_MS)
  }

  function flush(): void {
    if (timer) clearTimeout(timer)
    timer = undefined
    const target = pendingProjectId
    if (!target) return
    pendingProjectId = undefined
    const body = settings.value
    queue = queue.then(() => save(target, body))
  }

  async function save(target: string, body: TableSettings): Promise<void> {
    const csrf = readCsrfToken()
    if (!csrf) {
      ElMessage.error('缺少 CSRF 凭据，表格设置未保存，请刷新后重试。')
      return
    }
    try {
      await workItemsApi.updateMyWorkItemTableSettings({ projectId: target, xXSRFTOKEN: csrf, workItemTableSettingsUpdateRequest: body })
    } catch (reason) {
      ElMessage.error(`表格设置保存失败：${problemMessage(await toApiProblem(reason))}`)
    }
  }

  watch(projectId, () => void load(), { immediate: true })
  onBeforeUnmount(flush)
  return { settings, state, load, update }
}
