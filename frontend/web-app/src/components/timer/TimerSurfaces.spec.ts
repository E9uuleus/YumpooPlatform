import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import TimerDock from './TimerDock.vue'
import TimerOrb from './TimerOrb.vue'
import TimerSettings from './TimerSettings.vue'

const dockProps = { side: 'left' as const, left: 0, top: 10, expanded: false, running: false, saved: false, busy: false, offline: false, duration: 0, hasWork: false, canResume: false, toggleDisabled: false }
const orbProps = { size: 56, side: null, detailWidth: 0, open: false, running: false, saved: false, busy: false, offline: false, duration: 0, hasWork: false, canResume: false, toggleDisabled: false }

describe('compact timer surfaces', () => {
  it('shows the dock state on the edge tab and offers a work picker when idle', async () => {
    const idle = mount(TimerDock, { props: { ...dockProps, expanded: true } })
    expect(idle.classes()).toContain('dock-left')
    expect(idle.get('.dock-title').text()).toBe('尚未选择工作')
    expect(idle.get('.dock-status').text()).toBe('未开始')
    await idle.get('[aria-label="查找工作项"]').trigger('click')
    expect(idle.emitted('toggle')).toHaveLength(1)
    const running = mount(TimerDock, { props: { ...dockProps, running: true, canResume: true, duration: 5_100_000 } })
    expect(running.get('.tab-time').text()).toBe('1h25m')
    expect(running.get('.dock-tab').attributes('aria-label')).toBe('计时中，展开计时侧边栏')
    await running.get('.dock-tab').trigger('keydown', { key: 'Enter' })
    expect(running.emitted('expand')).toHaveLength(1)
    const saved = mount(TimerDock, { props: { ...dockProps, saved: true, canResume: true, offline: true } })
    expect(saved.find('.tab-time').exists()).toBe(false)
    expect(saved.get('.dock-tab').attributes('aria-label')).toBe('等待连接，展开计时侧边栏')
  })

  it('draws the orb ring from the running minute and marks offline or idle states', () => {
    const running = mount(TimerOrb, { props: { ...orbProps, running: true, canResume: true, duration: 90_000 } })
    expect(running.get('.orb-shell').attributes('style')).toContain('--orb-size: 56px')
    expect(running.get('.ring-arc').attributes('style')).toContain('stroke-dashoffset: 50')
    expect(running.get('.timer-digits').text()).toContain('01:30')
    const idle = mount(TimerOrb, { props: { ...orbProps, offline: true } })
    expect(idle.find('.orb-idle').exists()).toBe(true)
    expect(idle.find('[aria-label="等待连接"]').exists()).toBe(true)
    expect(idle.get('.orb-disc').attributes('aria-label')).toBe('未开始计时，展开计时详情')
  })

  it('switches settings rows with the display style', async () => {
    const orb = mount(TimerSettings, { props: { preferences: { display: 'orb', orbSize: 'small', dockSide: 'right' }, pinned: false } })
    expect(orb.text()).toContain('悬浮球大小')
    expect(orb.find('[aria-checked="true"].is-active').text()).toBe('小')
    expect(orb.get('[role="switch"]').attributes('aria-checked')).toBe('false')
    const dock = mount(TimerSettings, { props: { preferences: { display: 'dock', orbSize: 'small', dockSide: 'right' }, pinned: true } })
    expect(dock.text()).toContain('停靠位置')
    await dock.findAll('.segmented button')[0]!.trigger('click')
    expect(dock.emitted('update')?.[0]).toEqual([{ dockSide: 'left' }])
    await dock.get('[role="switch"]').trigger('click')
    expect(dock.emitted('pin')).toHaveLength(1)
  })
})
