import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElPopover } from 'element-plus'
import type { TeamTimesheet } from '@yumpoo/api-client'

const api = vi.hoisted(() => ({ getMyTimesheet: vi.fn(), push: vi.fn() }))
vi.mock('../../api/client', () => ({ myWorkApi: api }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {}, params: { workspaceSlug: 'workspace' } }),
  useRouter: () => ({ push: api.push }),
}))
vi.mock('../../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ authentication: ref({ company: { timezone: 'Asia/Shanghai' } }) }) }
})
vi.mock('../../composables/useTimeTracker', async () => {
  const { ref } = await import('vue')
  return {
    useTimeTracker: () => ({ now: ref(new Date('2026-10-15T04:00:00Z').getTime()), running: ref([]) }),
    onTimeTrackingChanged: () => () => undefined,
  }
})
import MyTimeView from './MyTimeView.vue'
import { toApiDate } from '../team/teamDashboardModel'

const sheet: TeamTimesheet = {
  from: toApiDate('2026-10-01'), to: toApiDate('2026-10-31'), timezone: 'Asia/Shanghai', asOf: new Date('2026-10-15T04:00:00Z'), days: [],
  members: [{ userId: 'u', displayName: '本人', active: true }],
  workItems: [{ id: 'w', projectId: 'p', projectName: '项目', itemNo: 'P-1', title: '工作项' }],
  entries: ['2026-10-08', '2026-10-09'].map(date => ({ userId: 'u', workItemId: 'w', date: toApiDate(date), durationMs: 3_600_000 })),
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  api.getMyTimesheet.mockReset().mockResolvedValue(sheet)
})
afterEach(() => vi.useRealTimers())

async function openAndScheduleSwitch() {
  const wrapper = mount(MyTimeView, { attachTo: document.body, global: { directives: { loading: () => undefined } } })
  await flushPromises()
  await wrapper.get('[aria-label^="10月8日 "]').trigger('mouseenter')
  await wrapper.get('[aria-label^="10月9日 "]').trigger('mouseenter')
  return wrapper
}

describe('工时月历日期明细', () => {
  it('进入另一日期格后停留一百六十毫秒才切换', async () => {
    const wrapper = await openAndScheduleSwitch()
    await vi.advanceTimersByTimeAsync(159)
    expect(wrapper.get('.my-time-cell.is-active').attributes('aria-label')).toContain('10月8日')
    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.get('.my-time-cell.is-active').attributes('aria-label')).toContain('10月9日')
    wrapper.unmount()
  })

  it.each(['Escape', 'outside', 'month'])('%s 关闭明细后不被待切换的定时器重新打开', async action => {
    const wrapper = await openAndScheduleSwitch()
    if (action === 'Escape') document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    else if (action === 'outside') document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    else await wrapper.get('[aria-label="上个月"]').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(200)
    expect(wrapper.find('.my-time-cell.is-active').exists()).toBe(false)
    expect(wrapper.findComponent(ElPopover).exists()).toBe(false)
    wrapper.unmount()
  })
})
