import { ResponseError, WorkItemTableHeight, type WorkItemTableSettings, type WorkItemTableSettingsUpdateRequest } from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { emptyTableSettings } from './workItemTableSettings'
import { useWorkItemTableSettings } from './useWorkItemTableSettings'

const api = vi.hoisted(() => ({ getMyWorkItemTableSettings: vi.fn(), updateMyWorkItemTableSettings: vi.fn() }))
vi.mock('../../api/client', () => ({ workItemsApi: api }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<object>(), readCsrfToken: () => 'csrf' }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))
enableAutoUnmount(afterEach)

let server: WorkItemTableSettings & { etag: string }
let version: number
function harness() {
  const project = ref('project-1')
  let prefs!: ReturnType<typeof useWorkItemTableSettings>
  const wrapper = mount(defineComponent({ setup() {
    prefs = useWorkItemTableSettings(() => project.value)
    return () => null
  } }))
  return { prefs, project, wrapper }
}
async function save() { await vi.advanceTimersByTimeAsync(401); await flushPromises() }
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  vi.resetAllMocks()
  version = 0
  server = { ...emptyTableSettings(), projectId: 'project-1', updatedAt: null, etag: '"0"' }
  api.getMyWorkItemTableSettings.mockImplementation(async () => structuredClone(server))
  api.updateMyWorkItemTableSettings.mockImplementation(async (request: {
    ifMatch?: string; workItemTableSettingsUpdateRequest: WorkItemTableSettingsUpdateRequest
  }) => {
    if (request.ifMatch && request.ifMatch !== server.etag)
      throw new ResponseError(new Response(JSON.stringify({ code: 'VERSION_CONFLICT', message: '版本冲突', requestId: 'r', retryable: false, fieldErrors: [], details: {} }), { status: 412 }))
    server = { ...server, ...structuredClone(request.workItemTableSettingsUpdateRequest), etag: `"${++version}"` }
    return structuredClone(server)
  })
})
afterEach(() => vi.useRealTimers())

describe('个人表格设置保存', () => {
  it('两个实例分别修改不同设置时保留双方修改', async () => {
    const a = harness().prefs, b = harness().prefs
    await flushPromises()
    a.update({ pinnedColumnCount: 2 })
    await save()
    b.update({ rowHeight: WorkItemTableHeight.Triple })
    await save()
    expect(server.pinnedColumnCount).toBe(2)
    expect(server.rowHeight).toBe(WorkItemTableHeight.Triple)
    expect(b.settings.value.pinnedColumnCount).toBe(2)
  })

  it('不同窗口修改不同默认字段时保留双方默认值', async () => {
    const a = harness().prefs, b = harness().prefs
    await flushPromises()
    a.update({ defaultValues: { ...a.settings.value.defaultValues, priority: 'HIGH' } })
    await save()
    b.update({ defaultValues: { ...b.settings.value.defaultValues, dueDateOffsetDays: 3 } })
    await save()
    expect(server.defaultValues).toMatchObject({ priority: 'HIGH', dueDateOffsetDays: 3 })
  })

  it('相同设置冲突保留本地编辑，继续编辑其他字段也不会自动覆盖，手动重试才提交', async () => {
    const a = harness().prefs, b = harness().prefs
    await flushPromises()
    a.update({ rowHeight: WorkItemTableHeight.Double }); await save()
    b.update({ rowHeight: WorkItemTableHeight.Triple }); await save()
    expect(server.rowHeight).toBe(WorkItemTableHeight.Double)
    expect(b.settings.value.rowHeight).toBe(WorkItemTableHeight.Triple)
    expect(b.saveState.value).toBe('error')
    expect(b.saveError.value).toContain('其他窗口')
    b.update({ pinnedColumnCount: 2 }); await save()
    expect(api.updateMyWorkItemTableSettings).toHaveBeenCalledTimes(2)
    b.retrySave(); await flushPromises()
    expect(server).toMatchObject({ rowHeight: WorkItemTableHeight.Triple, pinnedColumnCount: 2 })
    expect(b.saveState.value).toBe('saved')
  })

  it('关闭有冲突的表格不会隐式覆盖另一窗口的设置', async () => {
    const a = harness().prefs, b = harness()
    await flushPromises()
    a.update({ pinnedColumnCount: 1 }); await save()
    b.prefs.update({ pinnedColumnCount: 2 }); await save()
    b.wrapper.unmount(); await flushPromises()
    expect(server.pinnedColumnCount).toBe(1)
    expect(api.updateMyWorkItemTableSettings).toHaveBeenCalledTimes(2)
  })

  it('请求进行中继续编辑会串行保存，并保持最新界面值', async () => {
    const { prefs } = harness()
    await flushPromises()
    const write = api.updateMyWorkItemTableSettings.getMockImplementation()!
    let release!: () => void
    const waiting = new Promise<void>(resolve => { release = resolve })
    api.updateMyWorkItemTableSettings.mockImplementationOnce(async request => { await waiting; return write(request) })
    prefs.update({ rowHeight: WorkItemTableHeight.Double }); await save()
    expect(prefs.saveState.value).toBe('saving')
    prefs.update({ rowHeight: WorkItemTableHeight.Triple }); await save()
    expect(api.updateMyWorkItemTableSettings).toHaveBeenCalledTimes(1)
    release(); await flushPromises()
    expect(api.updateMyWorkItemTableSettings).toHaveBeenCalledTimes(2)
    expect(server.rowHeight).toBe(WorkItemTableHeight.Triple)
    expect(prefs.settings.value.rowHeight).toBe(WorkItemTableHeight.Triple)
    expect(prefs.saveState.value).toBe('saved')
  })

  it('网络失败保留改动并有限重试，恢复后可手动保存', async () => {
    const { prefs } = harness()
    await flushPromises()
    const write = api.updateMyWorkItemTableSettings.getMockImplementation()!
    api.updateMyWorkItemTableSettings.mockRejectedValue(new TypeError('offline'))
    prefs.update({ rowHeight: WorkItemTableHeight.Triple }); await save()
    expect(prefs.saveState.value).toBe('error')
    expect(prefs.settings.value.rowHeight).toBe(WorkItemTableHeight.Triple)
    await vi.advanceTimersByTimeAsync(4_000); await flushPromises()
    expect(api.updateMyWorkItemTableSettings).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.updateMyWorkItemTableSettings).toHaveBeenCalledTimes(3)
    api.updateMyWorkItemTableSettings.mockImplementation(write)
    prefs.retrySave(); await flushPromises()
    expect(server.rowHeight).toBe(WorkItemTableHeight.Triple)
    expect(prefs.saveState.value).toBe('saved')
    expect(prefs.saveError.value).toBeUndefined()
  })

  it('项目切换时把待保存改动写回原项目，不污染新项目', async () => {
    const { prefs, project } = harness()
    await flushPromises()
    api.getMyWorkItemTableSettings.mockImplementation(async ({ projectId }) => ({ ...emptyTableSettings(), projectId, etag: '"0"', updatedAt: null }))
    prefs.update({ pinnedColumnCount: 2 })
    project.value = 'project-2'; await flushPromises()
    expect(api.updateMyWorkItemTableSettings).toHaveBeenCalledWith(expect.objectContaining({ projectId: 'project-1' }))
    expect(prefs.settings.value.pinnedColumnCount).toBe(0)
    expect(prefs.saveState.value).toBe('saved')
  })

  it('读取失败时不能用空默认值覆盖设置，重试读取成功后可编辑', async () => {
    api.getMyWorkItemTableSettings.mockRejectedValueOnce(new TypeError('offline'))
    const { prefs } = harness(); await flushPromises()
    prefs.update({ pinnedColumnCount: 2 }); await save()
    expect(prefs.state.value).toBe('error')
    expect(api.updateMyWorkItemTableSettings).not.toHaveBeenCalled()
    await prefs.load()
    expect(prefs.state.value).toBe('ready')
  })
})
