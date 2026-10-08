import { DOMWrapper, enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { WorkItemColoringTarget, WorkItemLabelColorToken, WorkItemTableHeight } from '@yumpoo/api-client'
import { ElPopover } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WorkItemTableSettingsMenu from './WorkItemTableSettingsMenu.vue'
import WorkItemColoringRulesPanel from './WorkItemColoringRulesPanel.vue'
import { emptyTableSettings } from './workItemTableSettings'

enableAutoUnmount(afterEach)
function menu() {
  return mount(WorkItemTableSettingsMenu, { attachTo: document.body, props: {
    settings: { ...emptyTableSettings(), coloringRules: [{ id: 'one', target: WorkItemColoringTarget.Row,
      colorToken: WorkItemLabelColorToken.Sky, column: null, operator: null, values: [] }] },
    state: 'ready', saveState: 'saved', disabled: false, pinColumns: [], pinWidthBudget: () => 0, defaultsCount: 0,
    catalog: { members: [], statuses: [], priorities: [], contents: [] }, defaultColumns: [],
  } })
}

describe('表格设置菜单浮层边界', () => {
  it.each([
    ['表头高度', '行高', 'rowHeight'],
    ['行高', '表头高度', 'headerHeight'],
  ] as const)('从%s移到%s后，旧菜单的延迟关闭不会关掉新菜单', async (from, to, field) => {
    vi.useFakeTimers()
    try {
      const wrapper = menu()
      await wrapper.get('.table-settings-toolbar-button').trigger('click'); await flushPromises()
      const trigger = (label: string) => new DOMWrapper(
        [...document.querySelectorAll('button[role="menuitem"]')].find(button => button.textContent?.includes(label))!,
      )
      const submenu = (label: string) => document.querySelector(`[role="menu"][aria-label="${label}"]`)!

      await trigger(from).trigger('mouseenter')
      await vi.advanceTimersByTimeAsync(80)
      await flushPromises()
      expect(new DOMWrapper(submenu(from)).isVisible()).toBe(true)

      await trigger(from).trigger('mouseleave')
      await trigger(to).trigger('mouseenter')
      await vi.advanceTimersByTimeAsync(80)
      await flushPromises()
      expect(new DOMWrapper(submenu(to)).isVisible()).toBe(true)

      await vi.advanceTimersByTimeAsync(200)
      await flushPromises()
      expect(new DOMWrapper(submenu(to)).isVisible()).toBe(true)
      expect(new DOMWrapper(submenu(from)).isVisible()).toBe(false)
      const triple = [...submenu(to).querySelectorAll('button')].find(button => button.textContent?.trim() === '三行')!
      await new DOMWrapper(triple).trigger('click')
      expect(wrapper.emitted('update')).toEqual([[{ [field]: WorkItemTableHeight.Triple }]])
      expect(wrapper.findComponent(ElPopover).props('visible')).toBe(false)
    } finally { vi.useRealTimers() }
  })

  it.each(['el-popper', 'el-overlay'])('点击不属于本菜单的 %s 会关闭菜单', async className => {
    const wrapper = menu()
    await wrapper.get('.table-settings-toolbar-button').trigger('click'); await flushPromises()
    const outside = document.createElement('div'); outside.className = className; document.body.append(outside)
    try {
      outside.dispatchEvent(new Event('pointerdown', { bubbles: true }))
      await flushPromises()
      expect(wrapper.findComponent(ElPopover).props('visible')).toBe(false)
    } finally { outside.remove() }
  })

  it('焦点在高度菜单入口时，Escape 关闭子菜单后主菜单仍可继续操作', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = menu()
      await wrapper.get('.table-settings-toolbar-button').trigger('click'); await flushPromises()
      const trigger = [...document.querySelectorAll<HTMLButtonElement>('button[role="menuitem"]')]
        .find(button => button.textContent?.includes('表头高度'))!
      trigger.focus()
      await new DOMWrapper(trigger).trigger('mouseenter')
      await vi.advanceTimersByTimeAsync(80)
      await flushPromises()
      const mainMenu = document.querySelector('[role="menu"][aria-label="表格设置"]')!
      const heightMenu = document.querySelector('[role="menu"][aria-label="表头高度"]')!
      expect(new DOMWrapper(heightMenu).isVisible()).toBe(true)

      await new DOMWrapper(trigger).trigger('keydown', { key: 'Escape', code: 'Escape' })
      await vi.advanceTimersByTimeAsync(300)
      await flushPromises()
      expect(new DOMWrapper(heightMenu).isVisible()).toBe(false)
      expect(new DOMWrapper(mainMenu).isVisible()).toBe(true)
      expect(wrapper.findComponent(ElPopover).props('visible')).toBe(true)
    } finally { vi.useRealTimers() }
  })

  it('颜色面板中的 Escape 只关闭颜色子面板，再次 Escape 才关闭设置菜单', async () => {
    const wrapper = menu()
    await wrapper.get('.table-settings-toolbar-button').trigger('click'); await flushPromises()
    const coloring = [...document.querySelectorAll('button[role="menuitem"]')].find(button => button.textContent?.trim() === '条件着色')
    expect(coloring).toBeDefined()
    await new DOMWrapper(coloring!).trigger('click'); await flushPromises()
    const panel = wrapper.getComponent(WorkItemColoringRulesPanel)
    await panel.get('.table-settings-swatch').trigger('click'); await flushPromises()
    await vi.waitFor(() => expect(panel.findComponent(ElPopover).props('visible')).toBe(true))
    await panel.get('.table-settings-swatch').trigger('keydown', { key: 'Escape' }); await flushPromises()
    expect(wrapper.findComponent(ElPopover).props('visible')).toBe(true)
    expect(panel.findComponent(ElPopover).props('visible')).toBe(false)
    await panel.get('.table-settings-swatch').trigger('keydown', { key: 'Escape' }); await flushPromises()
    expect(wrapper.findComponent(ElPopover).props('visible')).toBe(false)
  })

  it('保存失败持续显示未保存状态，并提供重试入口', async () => {
    const wrapper = menu()
    await wrapper.setProps({ saveState: 'error', saveError: '表格设置尚未保存：网络不可用' })
    expect(wrapper.get('.table-settings-toolbar-button').text()).toContain('未保存')
    await wrapper.get('.table-settings-toolbar-button').trigger('click'); await flushPromises()
    const notice = document.querySelector('.table-settings-save-state')!
    expect(notice.textContent).toContain('网络不可用')
    await new DOMWrapper(notice.querySelector('button')!).trigger('click')
    expect(wrapper.emitted('retrySave')).toHaveLength(1)
  })
})
