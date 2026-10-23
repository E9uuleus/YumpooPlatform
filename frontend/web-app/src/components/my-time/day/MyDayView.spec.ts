import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TimeSessionFillMode } from '@yumpoo/api-client'
import MyDayView from './MyDayView.vue'
import { atMinute, dayWindow, type DayBlock } from './dayModel'

const api = vi.hoisted(() => ({ create: vi.fn(), edit: vi.fn(), discardDraft: vi.fn(), problem: '' }))
const onboardingReady = vi.hoisted(() => vi.fn())
vi.mock('../../../onboarding/useOnboarding', () => ({ usePageOnboarding: () => ({ ready: onboardingReady }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: {} }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))
vi.mock('../../../composables/useTimeTracker', () => ({ useTimeTracker: () => ({ stop: vi.fn() }) }))
vi.mock('./useDayTimeline', async () => {
  const { ref } = await import('vue')
  const { atMinute, dayWindow } = await import('./dayModel')
  const bounds = dayWindow('2026-10-08', 'Asia/Shanghai')
  return { useDayTimeline: () => ({
    page: ref({}), day: ref(), blocks: ref([]), layoutMap: ref(new Map()), shareMap: ref(new Map()), totals: ref([]),
    recordedMs: ref(0), dayRange: ref(bounds), workday: ref(bounds), now: ref(atMinute('2026-10-08', 1080, 'Asia/Shanghai')),
    limit: ref(bounds.end), loading: ref(false), busy: ref(false), problem: ref(api.problem), fresh: ref(new Set()),
    preferences: ref({ timerEnabled: true }), load: vi.fn(), hold: () => vi.fn(), create: api.create, edit: api.edit,
    remove: vi.fn(), reassign: vi.fn(), exclusive: vi.fn(),
  }) }
})

const timelineStub = defineComponent({
  name: 'DayTimeline',
  emits: ['drop', 'change'],
  setup(_props, { expose }) {
    expose({ discardDraft: api.discardDraft })
    return () => h('div')
  },
})
const date = '2026-10-08', timezone = 'Asia/Shanghai'
const range = { start: atMinute(date, 600, timezone), end: atMinute(date, 660, timezone) }
const item = { key: 'new', title: '新增工作项', itemNo: null, projectName: null, colorToken: null }
const original: DayBlock = { id: 'a', workItemId: 'a', timeCategory: null, subjectKey: 'a', projectId: 'p', title: '原工作项', itemNo: null, projectName: null,
  colorToken: null, start: range.start, end: range.end, canEdit: true, etag: '"1"' }
function mountDay() {
  return mount(MyDayView, { props: { date, today: date, timezone }, global: { stubs: {
    DayTimeline: timelineStub, DayHeader: true, ItemShelf: true, TimePreferencesPopover: true,
    EntryComposer: true, BlockPopover: true, ElPopover: true,
  } } })
}

beforeEach(() => { vi.clearAllMocks(); api.problem = ''; api.create.mockResolvedValue(true); api.edit.mockResolvedValue(true) })

describe('日时间线写入路由', () => {
  it.each([TimeSessionFillMode.Gaps, TimeSessionFillMode.Parallel])('拖入时保留时间线判定的 %s 模式和时段', async mode => {
    const wrapper = mountDay()
    wrapper.findComponent(timelineStub).vm.$emit('drop', item, range, mode)
    await flushPromises()
    expect(api.create).toHaveBeenCalledExactlyOnceWith(mode, ['new'], [range])
    expect(api.edit).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('修改失败时清除草稿，即使刷新失败也不遗留错误位置', async () => {
    api.edit.mockResolvedValue(false)
    const wrapper = mountDay()
    const moved = { start: range.start + 900_000, end: range.end + 900_000 }
    wrapper.findComponent(timelineStub).vm.$emit('change', original, moved)
    await flushPromises()
    expect(api.edit).toHaveBeenCalledExactlyOnceWith(original, moved)
    expect(api.discardDraft).toHaveBeenCalledExactlyOnceWith('a')
    wrapper.unmount()
  })

  it('保存成功后保留草稿直到现有数据刷新接管', async () => {
    const wrapper = mountDay()
    wrapper.findComponent(timelineStub).vm.$emit('change', original, { start: range.start, end: dayWindow(date, timezone).end })
    await flushPromises()
    expect(api.discardDraft).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('我的一天页面导览', () => {
  it('starts onboarding once the day has loaded', async () => {
    const wrapper = mountDay()
    await flushPromises()
    expect(onboardingReady).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('does not start onboarding when the day fails to load', async () => {
    api.problem = '加载失败'
    const wrapper = mountDay()
    await flushPromises()
    expect(onboardingReady).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
