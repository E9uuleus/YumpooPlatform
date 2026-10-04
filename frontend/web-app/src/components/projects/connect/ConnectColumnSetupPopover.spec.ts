import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElCheckbox, ElInput } from 'element-plus'
import { ProjectActorAccess, ProjectLifecycle, ResponseError } from '@yumpoo/api-client'
import ConnectColumnSetupPopover from './ConnectColumnSetupPopover.vue'
import ConnectBoardsIllustration from './ConnectBoardsIllustration.vue'
import { connectionTestContext } from './connectTestSupport'
import { connectColumn } from './connectTestFixtures'

const api = vi.hoisted(() => ({ searchConnectTargetProjects: vi.fn() }))
vi.mock('../../../api/client', () => ({ workItemsApi: api }))
vi.mock('../../../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { user: { id: 'user' }, company: { id: 'company' } } } }) }))
enableAutoUnmount(afterEach)
const projects = [{ id: 'source-project', code: 'P012', name: '本项目' }, ...Array.from({ length: 21 }, (_, index) => ({ id: `target-${index}`, code: `P${index}`, name: `项目 ${index}` }))]
beforeEach(() => {
  localStorage.clear(); document.body.innerHTML = ''
  api.searchConnectTargetProjects.mockReset()
  api.searchConnectTargetProjects.mockResolvedValue({ items: projects, page: 0, size: 20, totalElements: projects.length, totalPages: 1 })
})
function render(mode: 'create' | 'edit' = 'create', submit = vi.fn(async () => connectColumn)) {
  const provided = connectionTestContext()
  const wrapper = mount(ConnectColumnSetupPopover, { attachTo: document.body, global: provided.global, props: { visible: true, mode, projectId: 'source-project',
    projectName: '华东现场实施', submit, ...(mode === 'edit' ? { column: connectColumn } : {}) } })
  return { wrapper, submit, ...provided }
}
const rows = () => [...document.querySelectorAll<HTMLElement>('.connect-setup__project')]
const primary = (wrapper: ReturnType<typeof render>['wrapper']) => wrapper.findAllComponents(ElButton).at(-1)!

describe('Monday 式连接列设置弹层', () => {
  it('新建先展示引导插画，选择项目后列出最近使用与全部项目并排除当前项目', async () => {
    localStorage.setItem('yumpoo.projects.recents.v1.company%3Auser', JSON.stringify([
      { id: 'target-3', code: 'P3', name: '项目 3', lifecycle: ProjectLifecycle.Active, ownerUserId: 'u', ownerDisplayName: '张三', actorAccess: ProjectActorAccess.Member,
        createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', openedAt: 2, pinned: false },
      { id: 'source-project', code: 'P012', name: '本项目', lifecycle: ProjectLifecycle.Active, ownerUserId: 'u', ownerDisplayName: '张三', actorAccess: ProjectActorAccess.Member,
        createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', openedAt: 1, pinned: false }]))
    const { wrapper, context } = render(); await flushPromises()
    expect(wrapper.findComponent(ConnectBoardsIllustration).exists()).toBe(true)
    expect(document.body.textContent).toContain('在当前表格中查看和编辑其他项目的工作项')
    expect(context.searchTargets).not.toHaveBeenCalled()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '选择项目')!.trigger('click'); await flushPromises()
    expect(context.searchTargets).toHaveBeenCalledWith('', 0, expect.any(AbortSignal))
    expect([...document.querySelectorAll('.connect-setup__caption')].map(node => node.textContent?.trim())).toEqual(['最近使用', '全部项目'])
    expect(rows().map(row => row.querySelector('strong')?.textContent)).not.toContain('本项目')
    expect(rows()[0]!.querySelector('strong')?.textContent).toBe('项目 3')
    expect(rows().filter(row => row.querySelector('strong')?.textContent === '项目 3')).toHaveLength(1)
    expect(document.body.textContent).toContain('将创建双向连接所选项目的表格会自动出现「华东现场实施」列')
  })
  it('最多选择 20 个项目，点击行与复选框各切换一次，提交所选项目', async () => {
    const { wrapper, submit } = render(); await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '选择项目')!.trigger('click'); await flushPromises()
    expect(primary(wrapper).props('disabled')).toBe(true)
    rows()[0]!.click(); await flushPromises()
    expect(wrapper.findAllComponents(ElCheckbox)[0]!.props('modelValue')).toBe(true)
    rows()[0]!.querySelector<HTMLInputElement>('input')!.click(); await flushPromises()
    expect(wrapper.findAllComponents(ElCheckbox)[0]!.props('modelValue')).toBe(false)
    for (const row of rows()) row.click()
    await flushPromises()
    expect(document.querySelector('.connect-setup__count')?.textContent).toContain('已选 20 个项目 · 已达上限')
    expect(wrapper.findAllComponents(ElCheckbox).at(-1)!.props('disabled')).toBe(true)
    await primary(wrapper).trigger('click'); await flushPromises()
    expect(submit).toHaveBeenCalledOnce()
    expect((submit.mock.calls[0] as unknown as [Array<{ id: string }>])[0]).toHaveLength(20)
  })
  it('编辑直接进入项目列表并预选现有目标，未变化不可保存，失败时显示问题', async () => {
    const failure = new ResponseError(new Response(JSON.stringify({ code: 'INVALID_STATE_TRANSITION', message: '冲突', requestId: 'test', retryable: false, fieldErrors: [],
      details: { reason: 'CONNECT_TARGET_IN_USE', targetProjectId: connectColumn.targets[0]!.projectId, activeConnectionCount: 2 } }), { status: 409 }))
    const { wrapper, submit } = render('edit', vi.fn(async () => { throw failure }))
    await flushPromises()
    expect(wrapper.findComponent(ConnectBoardsIllustration).exists()).toBe(false)
    expect(document.body.textContent).not.toContain('返回')
    expect(primary(wrapper).text()).toBe('保存')
    expect(primary(wrapper).props('disabled')).toBe(true)
    rows().find(row => row.querySelector('strong')?.textContent === '项目 0')!.click(); await flushPromises()
    await primary(wrapper).trigger('click'); await flushPromises()
    expect(submit).toHaveBeenCalledOnce()
    expect(document.querySelector('[role="alert"], .inline-problem')).not.toBeNull()
  })
  it('搜索 300ms 防抖，Esc 关闭弹层', async () => {
    vi.useFakeTimers()
    try {
      const { wrapper, context } = render('edit'); await flushPromises()
      wrapper.getComponent(ElInput).vm.$emit('update:modelValue', '门户'); await flushPromises()
      expect(context.searchTargets).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(300)
      expect(context.searchTargets).toHaveBeenLastCalledWith('门户', 0, expect.any(AbortSignal))
      document.querySelector<HTMLElement>('.connect-setup')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
      expect(wrapper.emitted('update:visible')).toEqual([[false]])
    } finally { vi.useRealTimers() }
  })
})
