import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import TimerDock from './TimerDock.vue'
import TimerOrb from './TimerOrb.vue'
import TimerSettings from './TimerSettings.vue'

const dockProps = { side: 'left' as const, left: 0, top: 10, expanded: false, active: false, dragging: false, running: false, saved: false, busy: false, offline: false, duration: 0, hasWork: false, canResume: false, toggleDisabled: false }
const orbProps = { size: 56, side: null, detailWidth: 0, open: false, active: false, dragging: false, running: false, saved: false, busy: false, offline: false, duration: 0, hasWork: false, canResume: false, toggleDisabled: false }

describe('compact timer surfaces', () => {
  it('reads the dock tab as stacked values with units and offers a work picker when idle', async () => {
    const idle = mount(TimerDock, { props: { ...dockProps, expanded: true } })
    expect(idle.classes()).toContain('dock-left')
    expect(idle.get('.dock-title').text()).toBe('尚未选择工作')
    expect(idle.get('.dock-status').text()).toBe('未开始')
    expect(idle.get('.tab-hint').text()).toBe('开始计时')
    await idle.get('[aria-label="查找工作项"]').trigger('click')
    expect(idle.emitted('toggle')).toHaveLength(1)
    const running = mount(TimerDock, { props: { ...dockProps, running: true, canResume: true, duration: 5_130_000 } })
    expect(running.get('.tab-reading').text()).toBe('1时25分')
    expect(running.find('.tab-pulse').exists()).toBe(true)
    expect(running.get('.tab-arc').attributes('style')).toContain('stroke-dashoffset: 50')
    expect(running.get('.dock-tab').attributes('aria-label')).toBe('计时中，展开计时侧边栏')
    await running.get('.dock-tab').trigger('keydown', { key: 'Enter' })
    expect(running.emitted('expand')).toHaveLength(1)
    const saved = mount(TimerDock, { props: { ...dockProps, saved: true, canResume: true, offline: true, duration: 1_508_000 } })
    expect(saved.get('.tab-reading').text()).toBe('25分08秒')
    expect(saved.get('.dock-tab').attributes('aria-label')).toBe('等待连接，展开计时侧边栏')
  })

  it('detaches the dock from the screen edge while it is dragged', async () => {
    const dock = mount(TimerDock, { props: { ...dockProps, side: 'right', active: true } })
    expect(dock.classes()).toEqual(expect.arrayContaining(['dock-right', 'is-active']))
    await dock.setProps({ active: false, dragging: true })
    expect(dock.classes()).toContain('is-dragging')
  })

  it('shows the time at rest and morphs it into the play/pause control on hover, focus or when idle', async () => {
    const running = mount(TimerOrb, { props: { ...orbProps, running: true, canResume: true, duration: 90_000 } })
    expect(running.get('.orb-shell').attributes('style')).toContain('--orb-size: 56px')
    expect(running.get('.ring-arc').attributes('style')).toContain('stroke-dashoffset: 50')
    expect(running.get('.timer-digits').text()).toContain('01:30')
    expect(running.get('.orb-shell').classes()).not.toContain('is-control')
    expect(running.get('.orb-control').attributes('aria-label')).toBe('暂停计时')
    await running.setProps({ active: true })
    expect(running.get('.orb-shell').classes()).toContain('is-control')
    await running.get('.orb-control').trigger('click')
    await running.get('.orb-control').trigger('pointerdown', { button: 0 })
    expect(running.emitted('toggle')).toHaveLength(1)
    expect(running.emitted('press')).toHaveLength(1)
    expect(running.emitted('drag')).toBeUndefined()
    await running.setProps({ dragging: true })
    expect(running.get('.orb-shell').classes()).not.toContain('is-control')
    const idle = mount(TimerOrb, { props: { ...orbProps, offline: true } })
    expect(idle.get('.orb-shell').classes()).toContain('is-control')
    expect(idle.find('.timer-digits').exists()).toBe(false)
    expect(idle.get('.orb-control').attributes('aria-label')).toBe('查找工作项')
    expect(idle.find('[aria-label="等待连接"]').exists()).toBe(true)
    await idle.get('.orb-control').trigger('focus')
    expect(idle.emitted('reveal')).toHaveLength(1)
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
