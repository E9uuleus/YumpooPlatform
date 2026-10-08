import { WorkItemColoringColumn, WorkItemColoringOperator, WorkItemColoringTarget, WorkItemLabelColorToken, type WorkItemColoringRule } from '@yumpoo/api-client'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkItemColoringRulesPanel from './WorkItemColoringRulesPanel.vue'

enableAutoUnmount(afterEach)
const rule: WorkItemColoringRule = { id: 'one', target: WorkItemColoringTarget.Row, column: WorkItemColoringColumn.Title,
  operator: WorkItemColoringOperator.Contains, colorToken: WorkItemLabelColorToken.Sky, values: ['原文本'] }
const catalog = { members: [], statuses: [], priorities: [], contents: [] }
function panel() { return mount(WorkItemColoringRulesPanel, { props: { rules: [rule], catalog } }) }
beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }))
afterEach(() => vi.useRealTimers())

describe('着色条件文本编辑', () => {
  it('连续输入只在停顿后提交一次，保留完整输入', async () => {
    const wrapper = panel(), input = wrapper.get('input[aria-label="条件文本"]')
    await input.setValue('a'); await input.setValue('ab'); await input.setValue('abc')
    expect(wrapper.emitted('change')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(400)
    expect(wrapper.emitted('change')).toEqual([[[{ ...rule, values: ['abc'] }]]])
  })

  it('失焦或关闭面板会立即提交尚未到延迟时间的内容', async () => {
    const wrapper = panel(), input = wrapper.get('input[aria-label="条件文本"]')
    await input.setValue('失焦时保存'); await input.trigger('blur')
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toEqual([{ ...rule, values: ['失焦时保存'] }])
    await input.setValue('关闭时保存'); wrapper.unmount()
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toEqual([{ ...rule, values: ['关闭时保存'] }])
  })
})
