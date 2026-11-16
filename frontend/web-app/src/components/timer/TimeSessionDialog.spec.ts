import { defineComponent, h, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ listTimeSessions: vi.fn(), listMyTimeSessions: vi.fn(), fillMyTimeSessions: vi.fn(), editTimeSession: vi.fn(), deleteTimeSession: vi.fn() }))
vi.mock('../../api/client', () => ({ timeTrackingApi: api }))
vi.mock('../../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { company: { timezone: 'Asia/Shanghai' } } } }) }))
vi.mock('../../composables/useTimeTracker', () => ({ formatDuration: () => '0:00:01', formatTrackingDuration: () => '0m 1s', timerMutation: (_: string, call: (key: string, csrf: string) => unknown) => call('key', 'csrf') }))
import TimeSessionDialog from './TimeSessionDialog.vue'
import { ElMessage, ElPopover } from 'element-plus'
const record = (id: string, canEdit = true) => ({ id, workItemId: 'item', workItemNo: null, workItemTitle: null, source: 'MANUAL', rowVersion: 0, displayName: '测试成员', startedAt: new Date('2026-08-01T01:00:00Z'), stoppedAt: new Date('2026-08-01T01:00:01Z'), durationMs: 1000, allocatedDurationMs: 1000, parallelItemCount: 0, canEdit, etag: id })
const page = (items = [record('one')], nextCursor: string | null = null) => ({ items, nextCursor, canCreate: true, summary: { totalDurationMs: 1000 } })
async function setup() {
  const wrapper = mount(TimeSessionDialog, { props: { workItemId: 'item', title: '测试' } })
  await flushPromises()
  return wrapper
}
beforeEach(() => { vi.resetAllMocks(); api.listTimeSessions.mockResolvedValue(page()); api.listMyTimeSessions.mockResolvedValue({ items: [] }); api.fillMyTimeSessions.mockResolvedValue({ skippedDurationMs: 0 }); api.deleteTimeSession.mockResolvedValue({}) })
describe('计时日志分页与写入', () => {
  it('单条直接删除无需原因，请求期间阻止重复点击', async () => {
    const wrapper = await setup()
    let resolve!: (value: unknown) => void
    api.deleteTimeSession.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    await wrapper.get('.delete-button').trigger('click')
    await wrapper.get('.delete-button').trigger('click')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(api.deleteTimeSession).toHaveBeenCalledTimes(1)
    expect(api.deleteTimeSession.mock.calls[0]?.[0]).toMatchObject({ sessionId: 'one', ifMatch: 'one', timeTrackingCommand: {} })
    resolve({})
    await flushPromises()
    wrapper.unmount()
  })
  it('批量删除部分失败时报告已完成数量并重新加载', async () => {
    const message = vi.spyOn(ElMessage, 'error')
    const wrapper = await setup()
    api.listTimeSessions.mockResolvedValueOnce(page([record('one'), record('two')])).mockResolvedValueOnce(page([record('two')]))
    api.deleteTimeSession.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('conflict'))
    await wrapper.get('header button').trigger('click')
    await flushPromises()
    expect(message).toHaveBeenCalledWith('已清除 1 条记录，其余未完成，请刷新后重试。')
    expect(wrapper.findAll('.log-row')).toHaveLength(1)
    expect(wrapper.get('.delete-button').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
    message.mockRestore()
  })
  it('滚动触底追加下一页，并阻止重复加载', async () => {
    api.listTimeSessions.mockResolvedValueOnce(page([record('one')], 'next'))
    const wrapper = await setup()
    let resolve!: (value: unknown) => void
    api.listTimeSessions.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    await wrapper.get('.log-body').trigger('scroll')
    await wrapper.get('.log-body').trigger('scroll')
    expect(api.listTimeSessions).toHaveBeenCalledTimes(2)
    expect(api.listTimeSessions).toHaveBeenLastCalledWith({ workItemId: 'item', includeChildren: false, cursor: 'next' })
    resolve(page([record('two')]))
    await flushPromises()
    expect(wrapper.findAll('.log-row')).toHaveLength(2)
    wrapper.unmount()
  })
  it('清除遍历未加载分页，只删除有编辑权限的已停止记录', async () => {
    const wrapper = await setup()
    api.listTimeSessions.mockResolvedValueOnce(page([record('one'), record('other', false)], 'next')).mockResolvedValueOnce(page([record('two')])).mockResolvedValueOnce(page([]))
    await wrapper.get('header button').trigger('click')
    await flushPromises()
    expect(api.deleteTimeSession.mock.calls.map(call => call[0].sessionId)).toEqual(['one', 'two'])
    expect(wrapper.text()).toContain('暂无计时记录')
    wrapper.unmount()
  })
  it('日期和时间可编辑，分钟输入无需修正原因并保留修改标色', async () => {
    api.listTimeSessions.mockResolvedValue(page([{ ...record('one'), source: 'MANUAL', rowVersion: 1, stoppedAt: new Date('2026-08-01T02:00:00Z') }]))
    const wrapper = await setup()
    expect(wrapper.get('.record-date').classes()).toContain('is-edited')
    expect(wrapper.get('.time-range').classes()).toContain('is-edited')
    expect(wrapper.find('[title]').exists()).toBe(false)
    await wrapper.get('.record-date').trigger('click')
    expect(wrapper.text()).toContain('编辑时长记录')
    expect(wrapper.text()).not.toContain('修正原因')
    expect(wrapper.findAllComponents({ name: 'ElTimePicker' })).toHaveLength(2)
    await wrapper.get('header button').trigger('click')
    await wrapper.get('.time-range').trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.editTimeSession).toHaveBeenCalledOnce()
    expect(api.editTimeSession.mock.calls[0]?.[0].timeTrackingCommand).not.toHaveProperty('reason')
    wrapper.unmount()
  })
  it('手动添加成功后重新加载日志', async () => {
    const wrapper = await setup()
    await wrapper.get('.add-session button').trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.fillMyTimeSessions).toHaveBeenCalledOnce()
    expect(api.fillMyTimeSessions.mock.calls[0]?.[0].timeSessionFillCommand).toMatchObject({ mode: 'GAPS', entries: [{ workItemId: 'item' }] })
    expect(api.listTimeSessions).toHaveBeenCalledTimes(2)
    expect(wrapper.find('form').exists()).toBe(false)
    wrapper.unmount()
  })
})


describe('计时日志嵌套浮层', () => {
  async function click(element: Element) {
    for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
      element.dispatchEvent(new MouseEvent(type, { bubbles: true, composed: true, detail: 1 }))
    }
    await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 60))
  }

  it.each([false, true])('选择时间与结束日期保留表单，仅显式保存写入（编辑=%s）', async editing => {
    api.listTimeSessions.mockResolvedValue(page([{ ...record('one'), stoppedAt: new Date('2026-08-01T02:00:00Z') }]))
    const Host = defineComponent({
      setup() {
        const open = ref(true)
        return () => h(ElPopover, {
          visible: open.value, 'onUpdate:visible': (value: boolean) => { open.value = value },
          trigger: 'click', persistent: false, hideAfter: 0, popperClass: 'test-time-popover',
        }, {
          reference: () => h('button', '计时日志'),
          default: () => h(TimeSessionDialog, { workItemId: 'item', title: '测试', onClose: () => { open.value = false } }),
        })
      },
    })
    const wrapper = mount(Host, { attachTo: document.body, global: { stubs: { transition: false } } })
    try {
      await flushPromises()
      const dialog = wrapper.getComponent(TimeSessionDialog)
      await dialog.get(editing ? '.record-date' : '.add-session button').trigger('click')
      await flushPromises()
      const host = dialog.get('.time-log-picker-host').element
      for (const label of ['开始时间', '结束时间']) {
        const input = dialog.get<HTMLInputElement>(`input[aria-label="${label}"]`)
        const before = input.element.value
        input.element.focus()
        await click(input.element)
        await flushPromises()
        const panels = Array.from(host.querySelectorAll<HTMLElement>('.el-picker__popper'))
        const panel = panels.find(node => node.style.display !== 'none')!
        expect(panel).toBeTruthy()
        expect(panel.closest('.log-body')).toBeNull()
        expect(panel.closest('.test-time-popover')).not.toBeNull()
        const minutes = panel.querySelectorAll('.el-time-spinner__wrapper')[1]!.querySelectorAll('li')
        const minute = (Number(before.slice(3)) + 1) % 60
        await click(minutes[minute]!)
        await click(panel.querySelector('.el-time-panel__btn.confirm')!)
        expect(dialog.find('form').exists()).toBe(true)
        expect(input.element.value).toBe(`${before.slice(0, 3)}${String(minute).padStart(2, '0')}`)
        const confirmed = input.element.value
        input.element.focus()
        await click(input.element)
        await click(minutes[(minute + 1) % 60]!)
        await click(panel.querySelector('.el-time-panel__btn.cancel')!)
        expect(input.element.value).toBe(confirmed)
        expect(dialog.find('form').exists()).toBe(true)
        await click(input.element)
        await input.trigger('keydown', { code: 'Escape', key: 'Escape' })
        expect(dialog.find('form').exists()).toBe(true)
      }
      for (const label of ['次日结束', '当天结束']) {
        await click(dialog.get('.el-select__wrapper').element)
        const option = Array.from(host.querySelectorAll('.el-select-dropdown__item')).find(node => node.textContent === label)!
        expect(option).toBeTruthy()
        await click(option)
        expect(dialog.find('form').exists()).toBe(true)
      }
      expect(api.fillMyTimeSessions).not.toHaveBeenCalled()
      expect(api.editTimeSession).not.toHaveBeenCalled()
      // 开始/结束都向后移了一分钟，新增场景用过去的日期避开未来时间校验。
      const form = dialog.getComponent({ name: 'TimeSessionForm' })
      form.vm.$emit('update:start', '2026-08-01T09:01:00')
      form.vm.$emit('update:end', '2026-08-01T10:01:00')
      await flushPromises()
      await dialog.get('form').trigger('submit')
      await flushPromises()
      expect(editing ? api.editTimeSession : api.fillMyTimeSessions).toHaveBeenCalledTimes(1)
      expect(dialog.find('form').exists()).toBe(false)
      await click(document.body)
      await vi.waitFor(() => expect(document.querySelector('.time-log-shell')).toBeNull())
      expect(document.querySelector('.time-log-picker-host')).toBeNull()
    } finally {
      wrapper.unmount()
      document.body.innerHTML = ''
    }
  })
})
