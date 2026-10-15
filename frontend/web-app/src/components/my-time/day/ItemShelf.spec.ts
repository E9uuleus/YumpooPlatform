import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ listTimerCandidates: vi.fn() }))
vi.mock('../../../api/client', () => ({ timeTrackingApi: api }))
import ItemShelf from './ItemShelf.vue'
import { HOUR } from './dayModel'

const settle = async () => { await new Promise(resolve => setTimeout(resolve)); await flushPromises() }
const candidate = (id: string, title: string) => ({ workItemId: id, projectId: 'p', projectName: '项目', itemNo: id.toUpperCase(), title,
  contentName: '任务', contentCode: 'TASKS', statusCategory: 'TODO', assignedToMe: true, lastTrackedAt: null, ownDurationMs: 0 })
api.listTimerCandidates.mockResolvedValue({ items: [candidate('a', '写周报'), candidate('b', '评审')], nextOffset: null })
const totals = [{ workItemId: 'a', title: '写周报', itemNo: 'A', projectName: '项目', colorToken: null, ms: 1.5 * HOUR }]

describe('工作项侧栏', () => {
  it('今天已记录的项不在最近列表中重复出现', async () => {
    const wrapper = mount(ItemShelf, { props: { totals, busy: false }, attachTo: document.body })
    await settle()
    const sections = wrapper.findAll('.item-shelf__section')
    expect(sections[0]!.text()).toContain('写周报')
    expect(sections[0]!.text()).toContain('1h30m')
    expect(sections[1]!.text()).not.toContain('写周报')
    expect(sections[1]!.text()).toContain('评审')
    wrapper.unmount()
  })

  it('悬停时长预览排入位置，点击后按时长排入，自定义时长回车排入', async () => {
    const wrapper = mount(ItemShelf, { props: { totals: [], busy: false }, attachTo: document.body })
    await settle()
    await wrapper.get('.item-shelf__add').trigger('click')
    await settle()
    const chip = [...document.querySelectorAll<HTMLButtonElement>('.shelf-duration__chips button')].find(button => button.textContent?.trim() === '2h')!
    chip.dispatchEvent(new MouseEvent('mouseenter'))
    expect(wrapper.emitted('preview')!.at(-1)).toEqual([expect.objectContaining({ workItemId: 'a' }), 2 * HOUR])
    chip.click()
    expect(wrapper.emitted('place')![0]).toEqual([expect.objectContaining({ workItemId: 'a' }), 2 * HOUR])
    expect(wrapper.emitted('preview')!.at(-1)).toEqual([null, 0])
    await wrapper.findAll('.item-shelf__add')[1]!.trigger('click')
    await settle()
    const input = [...document.querySelectorAll<HTMLInputElement>('.shelf-duration input[aria-label="自定义时长"]')].at(-1)!
    input.value = '1h30'
    input.dispatchEvent(new Event('input'))
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    expect(wrapper.emitted('place')![1]).toEqual([expect.objectContaining({ workItemId: 'b' }), 1.5 * HOUR])
    wrapper.unmount()
  })
})
