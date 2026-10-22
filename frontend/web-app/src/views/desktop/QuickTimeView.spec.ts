import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import { TimeSessionFillMode, WorkItemLabelColorToken, type TimerCandidate } from '@yumpoo/api-client'
import QuickTimeView from './QuickTimeView.vue'
import { atMinute } from '../../components/my-time/day/dayModel'

const date = '2026-10-19', timezone = 'Asia/Shanghai'
const at = (minute: number) => atMinute(date, minute, timezone)
const api = vi.hoisted(() => ({
  create: vi.fn(), load: vi.fn(), announce: vi.fn(), getWorkItem: vi.fn(), getProject: vi.fn(), getLabels: vi.fn(),
  listContents: vi.fn(), listProjects: vi.fn(), transition: vi.fn(), createWorkItem: vi.fn(),
  state: undefined as undefined | { items: { value: TimerCandidate[] }; loading: { value: boolean } },
}))

vi.mock('@yumpoo/api-client', async importOriginal => ({ ...await importOriginal<object>(), readCsrfToken: () => 'csrf' }))
vi.mock('../../api/client', () => ({
  workItemsApi: { getWorkItem: api.getWorkItem, getProjectWorkItemLabels: api.getLabels, transitionWorkItem: api.transition, createWorkItem: api.createWorkItem },
  projectsApi: { getProject: api.getProject, listProjects: api.listProjects },
  contentsApi: { listProjectContents: api.listContents },
}))
vi.mock('../../composables/workItemChanges', () => ({ announceWorkItemChanged: api.announce }))
vi.mock('../../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ phase: ref('authenticated'),
    authentication: ref({ company: { timezone: 'Asia/Shanghai' }, user: { id: 'me', workspaceSlug: 'w' } }) }) }
})
vi.mock('../../composables/useTimeTracker', async () => {
  const { ref } = await import('vue')
  const { atMinute } = await import('../../components/my-time/day/dayModel')
  const tracker = { now: ref(atMinute('2026-10-19', 1080, 'Asia/Shanghai')), timerEnabled: ref(false), preferences: ref({}), start: vi.fn() }
  return { useTimeTracker: () => tracker }
})
vi.mock('../../composables/useDesktopSurfaceOpacity', () => ({ useDesktopSurfaceOpacity: vi.fn() }))
vi.mock('../../composables/useInfiniteSentinel', () => ({ useInfiniteSentinel: vi.fn() }))
vi.mock('../../composables/useTimerCandidates', async () => {
  const { ref } = await import('vue')
  return { useTimerCandidates: () => {
    const state = { query: ref(''), items: ref<TimerCandidate[]>([]), projectCounts: ref([]), hiddenCount: ref(0), loading: ref(false),
      problem: ref(''), nextOffset: ref(null), load: api.load }
    api.state = state
    return state
  } }
})
vi.mock('../../components/my-time/day/useDayTimeline', async () => {
  const { ref } = await import('vue')
  const { atMinute, dayWindow } = await import('../../components/my-time/day/dayModel')
  const day = dayWindow('2026-10-19', 'Asia/Shanghai'), now = atMinute('2026-10-19', 1080, 'Asia/Shanghai')
  return { useDayTimeline: () => ({
    page: ref({}), blocks: ref([]), totals: ref([]), recordedMs: ref(0), dayRange: ref(day), now: ref(now), limit: ref(now),
    workday: ref({ start: atMinute('2026-10-19', 540, 'Asia/Shanghai'), end: now }), busy: ref(false), create: api.create,
  }) }
})

const candidate: TimerCandidate = { workItemId: 'w1', projectId: 'p1', projectName: 'Yumpoo 平台', itemNo: 'P001-1', title: '设计评审',
  contentName: '需求', statusCategory: 'TODO', completed: false, assignedToMe: true, lastTrackedAt: null, ownDurationMs: 0 }
const statuses = [
  { code: 'NOT_STARTED', displayName: '未开始', colorToken: WorkItemLabelColorToken.EggYolk, statusCategory: 'TODO', sortOrder: 0, active: true },
  { code: 'DOING', displayName: '进行中', colorToken: WorkItemLabelColorToken.BrightGreen, statusCategory: 'IN_PROGRESS', sortOrder: 1, active: true },
]
const detail = (statusCode: string, statusCategory: string) => ({ id: 'w1', projectId: 'p1', title: '设计评审', itemNo: 'P001-1', statusCode, statusCategory,
  etag: `"${statusCode}"`, archived: false, capabilities: { canEditFields: true,
    availableTransitions: [{ toStatus: 'DOING', displayName: '进行中', statusCategory: 'IN_PROGRESS', requiresResolution: false }] } })
/** Opens on a capture-phase click, since the status button stops its own click from reaching the row. */
const popoverStub = defineComponent({
  props: { visible: Boolean },
  emits: ['update:visible'],
  setup(props, { slots, emit }) {
    return () => h('div', [h('span', { onClickCapture: () => emit('update:visible', !props.visible) }, slots.reference?.()),
      props.visible ? slots.default?.() : null])
  },
})

let wrapper: VueWrapper | undefined
async function mountView() {
  wrapper = mount(QuickTimeView, { attachTo: document.body, global: { plugins: [ElementPlus], stubs: { ElPopover: popoverStub, HideCompletedSwitch: true } } })
  api.state!.items.value = [{ ...candidate }]
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  api.load.mockResolvedValue(undefined)
  api.create.mockResolvedValue({ createdCount: 1, changedCount: 0, deletedCount: 0, skippedDurationMs: 0 })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

describe('快捷工时小窗', () => {
  it('改时段后按填写的起止记录，↺ 回到自动排入空档', async () => {
    const view = await mountView()
    await view.get('.quick-row').trigger('click')
    await view.get('.quick-hint__action').trigger('click')
    const fields = view.findAll('.quick-range input')
    expect(fields.map(field => (field.element as HTMLInputElement).value)).toEqual(['09:00', '10:00'])
    await fields[1]!.setValue('11:30')
    expect(view.get('.quick-range').text()).toContain('2 小时 30 分钟')
    expect(view.findAll('.quick-composer .quick-chip.is-active').map(chip => chip.text())).toEqual(['今天'])
    await view.get('.quick-button.is-primary').trigger('click')
    await flushPromises()
    expect(api.create).toHaveBeenCalledExactlyOnceWith(TimeSessionFillMode.Gaps, ['w1'], [{ start: at(540), end: at(690) }])

    // The row closes after its short "已记录" confirmation; opening it again starts from the automatic plan.
    await new Promise(resolve => setTimeout(resolve, 420))
    expect(view.find('.quick-item.is-expanded').exists()).toBe(false)
    await view.get('.quick-row').trigger('click')
    expect(view.find('.quick-range').exists()).toBe(false)
    await view.get('.quick-hint__action').trigger('click')
    await view.findAll('.quick-range input')[1]!.setValue('08:30')
    expect(view.get('.quick-range__note').text()).toBe('结束时间要晚于开始时间')
    expect(view.get('.quick-button.is-primary').attributes('disabled')).toBeDefined()
    await view.findAll('.quick-range .quick-hint__action').at(-1)!.trigger('click')
    expect(view.find('.quick-range').exists()).toBe(false)
    expect(view.get('.quick-hint').text()).toContain('排入今日空档 09:00 –')
  })

  it('点状态标签不展开行，选择后迁移状态并广播给其他窗口', async () => {
    api.getWorkItem.mockResolvedValue(detail('NOT_STARTED', 'TODO'))
    api.getProject.mockResolvedValue({ id: 'p1' })
    api.listContents.mockResolvedValue({ items: [] })
    api.getLabels.mockResolvedValue({ statuses, priorities: [] })
    api.transition.mockResolvedValue(detail('DOING', 'IN_PROGRESS'))
    const view = await mountView()
    expect(view.get('.quick-status').text()).toContain('待开始')
    await view.get('.quick-status').trigger('click')
    await flushPromises()
    expect(view.find('.quick-item.is-expanded').exists()).toBe(false)
    expect(api.getWorkItem).toHaveBeenCalledWith({ workItemId: 'w1' })
    expect(view.get('.quick-status').text()).toContain('未开始')
    const doing = view.findAll('.status-option-pill').find(pill => pill.text() === '进行中')!
    await doing.trigger('click')
    await flushPromises()
    expect(api.transition).toHaveBeenCalledWith(expect.objectContaining({ workItemId: 'w1', ifMatch: '"NOT_STARTED"',
      workItemTransitionRequest: { toStatus: 'DOING', resolution: null } }))
    expect(api.state!.items.value[0]!.statusCategory).toBe('IN_PROGRESS')
    expect(api.load).toHaveBeenCalled()
    expect(api.announce).toHaveBeenCalledExactlyOnceWith({ projectId: 'p1', workItemId: 'w1' })
    expect(view.get('.quick-toast').text()).toBe('已改为「进行中」· 设计评审')
  })

  it('新建的工作项分配给本人，创建后展开记录区并广播', async () => {
    api.listProjects.mockResolvedValue({ items: [{ id: 'p1', name: 'Yumpoo 平台' }] })
    api.listContents.mockResolvedValue({ items: [{ id: 'c2', active: true, sortOrder: 2 }, { id: 'c0', active: false, sortOrder: 0 },
      { id: 'c1', active: true, sortOrder: 1 }] })
    api.createWorkItem.mockResolvedValue({ id: 'w9', projectId: 'p1', itemNo: 'P001-9', title: '整理周报', contentName: '需求',
      contentColorToken: WorkItemLabelColorToken.EggYolk, statusCategory: 'TODO' })
    const view = await mountView()
    await view.get('.quick-search__add').trigger('click')
    await flushPromises()
    const title = view.get('.quick-create__title input')
    await title.setValue('整理周报')
    await title.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(api.createWorkItem).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ projectId: 'p1', xXSRFTOKEN: 'csrf',
      workItemCreateRequest: expect.objectContaining({ contentId: 'c1', title: '整理周报', assigneeUserIds: ['me'], assigneeUserId: 'me' }) }))
    expect(api.announce).toHaveBeenCalledExactlyOnceWith({ projectId: 'p1', workItemId: 'w9' })
    expect(view.find('.quick-create').exists()).toBe(false)
    expect(view.get('.quick-item.is-expanded .quick-row__title').text()).toBe('整理周报')
    expect(view.get('.quick-toast').text()).toBe('已创建 #P001-9 · 整理周报')
  })
})
