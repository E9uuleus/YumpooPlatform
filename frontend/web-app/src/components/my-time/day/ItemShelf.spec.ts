import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ listTimerCandidates: vi.fn() }))
vi.mock('../../../api/client', () => ({ timeTrackingApi: api }))
import ItemShelf from './ItemShelf.vue'
import { HOUR } from './dayModel'
import { draggedItem } from './shelfDrag'

const settle = async () => { await new Promise(resolve => setTimeout(resolve)); await flushPromises() }
const candidate = (id: string, title: string) => ({ workItemId: id, projectId: 'p', projectName: '项目', itemNo: id.toUpperCase(), title,
  contentName: '任务', contentCode: 'TASKS', statusCategory: 'TODO', assignedToMe: true, lastTrackedAt: null, ownDurationMs: 0 })
beforeEach(() => {
  api.listTimerCandidates.mockReset().mockResolvedValue({ items: [candidate('a', '写周报'), candidate('b', '评审')], nextOffset: null })
})
afterEach(() => vi.useRealTimers())
const totals = [{ key: 'a', title: '写周报', itemNo: 'A', projectName: '项目', colorToken: null, ms: 1.5 * HOUR }]

describe('工作项侧栏', () => {
  it('自动加载下一段，追加失败时保留原列表并从相同位置重试', async () => {
    api.listTimerCandidates.mockResolvedValueOnce({ items: [candidate('a', '写周报')], nextOffset: 25 }).mockRejectedValueOnce(new Error('offline'))
    const wrapper = mount(ItemShelf, { props: { totals: [], busy: false } })
    await settle()
    expect(wrapper.findAll('button').some(button => button.text() === '加载更多')).toBe(false)
    expect(wrapper.text()).toContain('加载更多失败')
    expect(wrapper.findAll('.item-shelf__row:not(.is-category)')).toHaveLength(1)
    api.listTimerCandidates.mockResolvedValueOnce({ items: [candidate('b', '评审')], nextOffset: null })
    await wrapper.get('.item-shelf__status button').trigger('click')
    await flushPromises()
    expect(api.listTimerCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ offset: 25 }), expect.anything())
    expect(wrapper.findAll('.item-shelf__row:not(.is-category)')).toHaveLength(2)
    wrapper.unmount()
  })
  it('今天已记录的项不在最近列表中重复出现', async () => {
    const wrapper = mount(ItemShelf, { props: { totals, busy: false }, attachTo: document.body })
    await settle()
    const sections = wrapper.findAll('.item-shelf__section')
    expect(sections[0]!.text()).toContain('写周报')
    expect(sections[0]!.text()).toContain('1h30m')
    expect(sections[1]!.text()).toContain('非项目工时')
    expect(sections[2]!.text()).not.toContain('写周报')
    expect(sections[2]!.text()).toContain('评审')
    wrapper.unmount()
  })

  it('非项目分类列在工作项上方，可按名称搜索，按项目筛选时隐藏', async () => {
    const wrapper = mount(ItemShelf, { props: { totals: [], busy: false }, attachTo: document.body })
    await settle()
    const fixed = () => wrapper.findAll('.item-shelf__row.is-category').map(row => row.text())
    expect(fixed()).toEqual([expect.stringContaining('出差'), expect.stringContaining('学习'), expect.stringContaining('商务'), expect.stringContaining('会议')])
    const row = wrapper.get('.item-shelf__row.is-category')
    await row.trigger('dragstart', { dataTransfer: { setData: vi.fn(), effectAllowed: '' } })
    expect(draggedItem.value).toMatchObject({ key: 'category:BUSINESS_TRIP', title: '出差' })
    await row.trigger('dragend')
    vi.useFakeTimers()
    await wrapper.get('input[aria-label="搜索工作项或分类"]').setValue('会议')
    await vi.advanceTimersByTimeAsync(200)
    expect(fixed()).toEqual([expect.stringContaining('会议')])
    vi.useRealTimers()
    wrapper.unmount()
  })

  it('今天已记录的分类留在今天一节，不在非项目工时中重复', async () => {
    const wrapper = mount(ItemShelf, { props: { totals: [{ key: 'category:MEETING', title: '会议', itemNo: null, projectName: '非项目工时',
      colorToken: null, ms: HOUR }], busy: false }, attachTo: document.body })
    await settle()
    const sections = wrapper.findAll('.item-shelf__section')
    expect(sections[0]!.text()).toContain('会议')
    expect(sections[1]!.text()).not.toContain('会议')
    wrapper.unmount()
  })

  it('悬停时长预览排入位置，点击后按时长排入，自定义时长回车排入', async () => {
    const wrapper = mount(ItemShelf, { props: { totals: [], busy: false }, attachTo: document.body })
    await settle()
    await wrapper.get('.item-shelf__row:not(.is-category) .item-shelf__add').trigger('click')
    await settle()
    const chip = [...document.querySelectorAll<HTMLButtonElement>('.shelf-duration__chips button')].find(button => button.textContent?.trim() === '2h')!
    chip.dispatchEvent(new MouseEvent('mouseenter'))
    expect(wrapper.emitted('preview')!.at(-1)).toEqual([expect.objectContaining({ key: 'a' }), 2 * HOUR])
    chip.click()
    expect(wrapper.emitted('place')![0]).toEqual([expect.objectContaining({ key: 'a' }), 2 * HOUR])
    expect(wrapper.emitted('preview')!.at(-1)).toEqual([null, 0])
    await wrapper.findAll('.item-shelf__row:not(.is-category) .item-shelf__add')[1]!.trigger('click')
    await settle()
    const input = [...document.querySelectorAll<HTMLInputElement>('.shelf-duration input[aria-label="自定义时长"]')].at(-1)!
    input.value = '1h30'
    input.dispatchEvent(new Event('input'))
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    expect(wrapper.emitted('place')![1]).toEqual([expect.objectContaining({ key: 'b' }), 1.5 * HOUR])
    wrapper.unmount()
  })

  it('搜索期间显示全部范围并禁用切换，清空后恢复原范围', async () => {
    const wrapper = mount(ItemShelf, { props: { totals: [], busy: false }, attachTo: document.body })
    await settle()
    vi.useFakeTimers()
    await wrapper.get('input[aria-label="搜索工作项或分类"]').setValue('周报')
    await vi.advanceTimersByTimeAsync(200)
    const scopes = wrapper.findAll('[role="radio"]')
    expect(scopes[1]!.attributes('aria-checked')).toBe('true')
    expect(scopes.every(option => option.attributes('disabled') !== undefined)).toBe(true)
    expect(api.listTimerCandidates.mock.calls.at(-1)![0]).toMatchObject({ q: '周报', scope: 'ALL' })
    const calls = api.listTimerCandidates.mock.calls.length
    await scopes[0]!.trigger('click')
    await vi.advanceTimersByTimeAsync(200)
    expect(api.listTimerCandidates).toHaveBeenCalledTimes(calls)
    await wrapper.get('input[aria-label="搜索工作项或分类"]').setValue('')
    await vi.advanceTimersByTimeAsync(0)
    expect(scopes[0]!.attributes('aria-checked')).toBe('true')
    expect(scopes.every(option => option.attributes('disabled') === undefined)).toBe(true)
    wrapper.unmount()
  })

  it('候选重载后保留已选项目和复选框，能单独取消零结果项目', async () => {
    const a = { ...candidate('a', '写周报'), projectId: 'pa', projectName: '项目 A' }
    const b = { ...candidate('b', '评审'), projectId: 'pb', projectName: '项目 B' }
    api.listTimerCandidates.mockImplementation(async ({ scope, projectId }) => ({
      items: (scope === 'ALL' ? [a, b] : [a]).filter(item => !projectId?.length || projectId.includes(item.projectId)), nextOffset: null,
      projects: (scope === 'ALL' ? [a, b] : [a]).map(item => ({ projectId: item.projectId, projectName: item.projectName, count: 1 })),
    }))
    const wrapper = mount(ItemShelf, { props: { totals: [], busy: false }, attachTo: document.body })
    await settle()
    await wrapper.findAll('[role="radio"]')[1]!.trigger('click')
    await settle()
    const projectTrigger = wrapper.findAll('.item-shelf__filter')[1]!
    await projectTrigger.trigger('click')
    await flushPromises()
    const projectB = () => [...document.querySelectorAll<HTMLElement>('.item-shelf-projects__option')].find(option => option.textContent?.includes('项目 B'))!
    projectB().querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await settle()
    expect(api.listTimerCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: ['pb'] }), expect.anything())
    expect(wrapper.get('.item-shelf__section h3').text()).toContain('筛选结果1')
    await projectTrigger.trigger('click')
    await wrapper.findAll('[role="radio"]')[0]!.trigger('click')
    await settle()
    await projectTrigger.trigger('click')
    await flushPromises()
    expect(projectB().textContent).toContain('0')
    expect(projectB().querySelector<HTMLInputElement>('input[type="checkbox"]')!.checked).toBe(true)
    expect(wrapper.text()).toContain('没有匹配的工作项')
    projectB().querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await settle()
    expect(wrapper.findAll('.item-shelf__row:not(.is-category)').map(row => row.text())).toEqual([expect.stringContaining('写周报')])
    wrapper.unmount()
  })

  it('项目选项采用首段全量计数，分配给我交给服务端筛选', async () => {
    api.listTimerCandidates.mockResolvedValue({ items: [candidate('a', '写周报')], nextOffset: null,
      projects: [{ projectId: 'unloaded', projectName: '尚未加载的项目', count: 31 }], hiddenCompletedCount: 40 })
    const wrapper = mount(ItemShelf, { props: { totals: [], busy: false }, attachTo: document.body })
    await settle()
    await wrapper.findAll('.item-shelf__filter')[1]!.trigger('click')
    await flushPromises()
    const options = [...document.querySelectorAll<HTMLElement>('.item-shelf-projects__option')]
    expect(options.map(option => option.textContent)).toEqual([expect.stringContaining('尚未加载的项目')])
    expect(options[0]!.textContent).toContain('31')
    await wrapper.findAll('.item-shelf__filter')[0]!.trigger('click')
    await settle()
    expect(api.listTimerCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ assignedToMe: true }), expect.anything())
    wrapper.unmount()
  })
})
