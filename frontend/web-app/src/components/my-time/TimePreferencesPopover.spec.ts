import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'

const tracker = vi.hoisted(() => ({ preferences: undefined as unknown, running: undefined as unknown, setPreferences: vi.fn(async () => true) }))
vi.mock('../../composables/useTimeTracker', async () => {
  const { ref } = await import('vue')
  tracker.preferences = ref({ timerEnabled: true, workdayStartMinute: 540, workdayEndMinute: 1080 })
  tracker.running = ref([{ session: { id: 's' } }, { session: { id: 't' } }])
  return { useTimeTracker: () => tracker }
})
import TimePreferencesPopover from './TimePreferencesPopover.vue'

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
})
