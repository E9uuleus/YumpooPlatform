import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Ref } from 'vue'
import { ElMessageBox, ElTimeSelect } from 'element-plus'

const tracker = vi.hoisted(() => ({ preferences: undefined as unknown, running: undefined as unknown, busy: undefined as unknown, setPreferences: vi.fn(async () => true) }))
vi.mock('../../composables/useTimeTracker', async () => {
  const { ref } = await import('vue')
  tracker.preferences = ref({ timerEnabled: true, workdayStartMinute: 540, workdayEndMinute: 1080 })
  tracker.running = ref([{ session: { id: 's' } }, { session: { id: 't' } }])
  tracker.busy = ref(false)
  return { useTimeTracker: () => tracker }
})
import TimePreferencesPopover from './TimePreferencesPopover.vue'

beforeEach(() => {
  vi.clearAllMocks()
  ;(tracker.busy as Ref<boolean>).value = false
  ;(tracker.running as Ref<unknown[]>).value = [{ session: { id: 's' } }, { session: { id: 't' } }]
  tracker.setPreferences.mockImplementation(async () => true)
})
afterEach(() => vi.restoreAllMocks())

describe('工时设置', () => {
  it('关闭计时器时先确认会停止正在计时的项，取消则不保存', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm' as never)
    const wrapper = mount(TimePreferencesPopover, { attachTo: document.body })
    await wrapper.get('.time-preferences__trigger button').trigger('click')
    await flushPromises()
    const toggle = document.querySelector<HTMLElement>('.time-preferences .el-switch')!
    toggle.click(); await flushPromises()
    expect(confirm.mock.calls[0]![0]).toContain('正在计时的 2 项会立即停止')
    expect(tracker.setPreferences).not.toHaveBeenCalled()
    toggle.click(); await flushPromises()
    expect(tracker.setPreferences).toHaveBeenCalledWith({ timerEnabled: false, workdayStartMinute: 540, workdayEndMinute: 1080 })
    wrapper.unmount()
  })

  it.each(['timer', 'workday'])('通过 %s 保存期间，计时器和上下班时间共用忙状态', async source => {
    const busy = tracker.busy as Ref<boolean>
    ;(tracker.running as Ref<unknown[]>).value = []
    let finish!: (value: boolean) => void
    tracker.setPreferences.mockImplementationOnce(() => {
      busy.value = true
      return new Promise<boolean>(resolve => { finish = resolve }).finally(() => { busy.value = false })
    })
    const wrapper = mount(TimePreferencesPopover, { attachTo: document.body })
    await wrapper.get('.time-preferences__trigger button').trigger('click')
    await flushPromises()
    const controls = wrapper.findAllComponents(ElTimeSelect)
    const toggle = document.querySelector<HTMLElement>('.time-preferences .el-switch')!
    if (source === 'timer') toggle.click()
    else controls[0]!.vm.$emit('change', '10:00')
    await flushPromises()
    expect(tracker.setPreferences).toHaveBeenCalledTimes(1)
    expect(controls).toHaveLength(2)
    expect(controls.every(control => control.props('disabled'))).toBe(true)
    expect(document.querySelector<HTMLInputElement>('.time-preferences input[role="switch"]')!.disabled).toBe(true)
    finish(true)
    await flushPromises()
    expect(controls.every(control => !control.props('disabled'))).toBe(true)
    expect(document.querySelector<HTMLInputElement>('.time-preferences input[role="switch"]')!.disabled).toBe(false)
    wrapper.unmount()
  })
})
