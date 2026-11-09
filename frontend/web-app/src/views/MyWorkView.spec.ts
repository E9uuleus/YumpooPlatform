import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ listTimerCandidates: vi.fn() }))
vi.mock('../api/client', () => ({ timeTrackingApi: api }))
vi.mock('../onboarding/useOnboarding', () => ({ usePageOnboarding: () => ({ ready: vi.fn() }) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {}, params: {} }), useRouter: () => ({ push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn(),
}))
vi.mock('../composables/useSession', async () => {
  const { ref } = await import('vue')
  return { useSession: () => ({ authentication: ref({ company: { id: 'company' }, user: { id: 'user' } }) }) }
})
vi.mock('../composables/useProjectRecents', async () => {
  const { ref } = await import('vue')
  return { useProjectRecents: () => ({ items: ref([]) }), orderByRecents: <T>(items: T[]) => items }
})
vi.mock('../components/my-work/myWorkTableSource', () => ({ myWorkTableSource: () => ({}) }))
vi.mock('../components/my-work/MyWorkItemDialog.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ render: () => null }) }
})
vi.mock('../components/projects/ProjectWorkItems.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({
    props: { groupField: { type: String, default: undefined } },
    setup: props => () => h('div', { class: 'table-stub', 'data-field': props.groupField }),
  }) }
})
import MyWorkView from './MyWorkView.vue'

enableAutoUnmount(afterEach)
const viewKey = 'yumpoo:my-work:view:v1:company:user'

async function mountPage() {
  const wrapper = mount(MyWorkView, { global: { directives: { loading: {} }, stubs: {
    RouterLink: true, InlineProblem: true, YpEmptyState: true, WorkItemGroupToggle: true,
    MyWorkViewMenu: {
      props: ['modelValue'], emits: ['update:modelValue'],
      template: '<button class="menu-stub" @click="$emit(\'update:modelValue\', \'PRIORITY\')">{{ modelValue }}</button>',
    },
  } } })
  await flushPromises()
  return wrapper
}
const tableFields = (wrapper: Awaited<ReturnType<typeof mountPage>>) => wrapper.findAll('.table-stub').map(table => table.attributes('data-field'))

beforeEach(() => {
  localStorage.clear()
  api.listTimerCandidates.mockResolvedValue({ projects: [
    { projectId: 'one', projectName: '项目一', count: 1 }, { projectId: 'two', projectName: '项目二', count: 2 },
  ] })
  vi.stubGlobal('IntersectionObserver', class {
    constructor(private readonly callback: IntersectionObserverCallback) {}
    observe(target: Element) { this.callback([{ target, isIntersecting: true } as IntersectionObserverEntry], this as unknown as IntersectionObserver) }
    unobserve() {}
    disconnect() {}
  })
})
afterEach(() => { vi.unstubAllGlobals() })

describe('我的工作视图', () => {
  it('默认状态视图并应用到所有项目', async () => {
    const wrapper = await mountPage()
    expect(wrapper.get('.menu-stub').text()).toBe('STATUS')
    expect(tableFields(wrapper)).toEqual(['STATUS', 'STATUS'])
  })

  it('读取本机保存的视图，非法值回退到默认视图', async () => {
    localStorage.setItem(viewKey, 'DUE_DATE')
    expect(tableFields(await mountPage())).toEqual(['DUE_DATE', 'DUE_DATE'])
    localStorage.setItem(viewKey, 'ASSIGNEE')
    expect(tableFields(await mountPage())).toEqual(['STATUS', 'STATUS'])
  })

  it('切换后所有项目同步分组并写入本机', async () => {
    const wrapper = await mountPage()
    await wrapper.get('.menu-stub').trigger('click')
    expect(tableFields(wrapper)).toEqual(['PRIORITY', 'PRIORITY'])
    expect(localStorage.getItem(viewKey)).toBe('PRIORITY')
  })
})
