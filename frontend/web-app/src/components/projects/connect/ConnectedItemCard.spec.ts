import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElDropdown, ElInput, ElMessageBox } from 'element-plus'
import { ResponseError } from '@yumpoo/api-client'
import ConnectedItemCard from './ConnectedItemCard.vue'
import WorkItemLabelPopoverContent from '../WorkItemLabelPopoverContent.vue'
import WorkItemContentPopoverContent from '../WorkItemContentPopoverContent.vue'
import { connectionTestContext } from './connectTestSupport'
import { connection } from './connectTestFixtures'
import { previewContents, previewDetail, previewLabels, previewProject } from '../../../visual-acceptance/connectColumnFixtures'

const api = vi.hoisted(() => ({ getWorkItem: vi.fn(), getProjectWorkItemLabels: vi.fn(), patchWorkItemPriority: vi.fn(), patchWorkItemContent: vi.fn(),
  transitionWorkItem: vi.fn(), updateWorkItem: vi.fn(), getProject: vi.fn(), listProjectContents: vi.fn(), listProjectMembers: vi.fn() }))
vi.mock('../../../api/client', () => ({ workItemsApi: api, projectsApi: { getProject: api.getProject, listProjectMembers: api.listProjectMembers },
  contentsApi: { listProjectContents: api.listProjectContents } }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))
vi.mock('../../../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { company: { id: 'company', timezone: 'Asia/Shanghai' }, user: { id: 'user' } } } }) }))
const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
enableAutoUnmount(afterEach)
const detail = previewDetail(connection.target)
beforeEach(() => {
  vi.resetAllMocks(); document.body.innerHTML = ''
  api.getWorkItem.mockResolvedValue(detail)
  api.getProject.mockResolvedValue(previewProject(connection.target))
  api.listProjectContents.mockResolvedValue(previewContents(connection.target.projectId))
  api.getProjectWorkItemLabels.mockResolvedValue(previewLabels)
  api.patchWorkItemPriority.mockImplementation(async ({ workItemPriorityPatchRequest }) => ({ ...detail, priority: workItemPriorityPatchRequest.priority, etag: '"4"' }))
  api.updateWorkItem.mockImplementation(async ({ workItemUpdateRequest }) => ({ ...detail, title: workItemUpdateRequest.title, etag: '"4"' }))
})
function render(props: Record<string, unknown> = {}) {
  const provided = connectionTestContext()
  vi.mocked(provided.context.getConnection).mockImplementation(async () => (props.connection as typeof connection | undefined) ?? connection)
  const wrapper = mount(ConnectedItemCard, { attachTo: document.body, props: { open: true, connection, perspective: 'source', ...props },
    global: { ...provided.global, stubs: { RouterLink: { template: '<a><slot /></a>' }, WorkItemCellActivityLog: { props: ['workItemId'], template: '<div class="activity-stub">{{ workItemId }}</div>' } } } })
  return { wrapper, ...provided }
}
const text = () => document.body.textContent ?? ''
const heading = () => document.querySelector<HTMLElement>('.connected-item-card__heading h2')!

describe('已连接工作项卡片', () => {
  it('可访问对端项目时加载完整字段，通过普通工作项接口修改并通知刷新连接', async () => {
    const { wrapper } = render(); await flushPromises()
    expect(api.getWorkItem).toHaveBeenCalledWith({ workItemId: connection.target.workItemId })
    expect([...document.querySelectorAll('.connected-item-card__row dt')].map(node => node.textContent?.trim()))
      .toEqual(['工作项类别', '处理人', '截止日期', '状态', '优先级', '最后更新', '报告人'])
    const priority = wrapper.findAllComponents(WorkItemLabelPopoverContent).find(component => component.props('kind') === 'priority')!
    expect(priority.props()).toMatchObject({ projectId: connection.target.projectId, currentValue: 'HIGH', canManage: false })
    priority.vm.$emit('selectPriority', 'LOW'); await flushPromises()
    expect(api.patchWorkItemPriority).toHaveBeenCalledWith(expect.objectContaining({ workItemId: detail.id, ifMatch: detail.etag,
      workItemPriorityPatchRequest: { priority: 'LOW' } }))
    expect(wrapper.emitted('changed')).toEqual([[connection]])
    expect(document.querySelector('.connected-item-card__tile.is-filled')?.textContent).toContain('缺陷')
    expect(wrapper.findComponent(WorkItemContentPopoverContent).props('catalog')?.items).toHaveLength(2)
  })
  it('点击标题内联编辑，以最新详情的完整快照保存', async () => {
    const { wrapper } = render(); await flushPromises()
    heading().click(); await flushPromises()
    wrapper.getComponent(ElInput).vm.$emit('update:modelValue', '打印服务调用超时（复现）')
    await wrapper.getComponent(ElInput).find('input').trigger('keydown', { key: 'Enter' }); await flushPromises()
    expect(api.updateWorkItem).toHaveBeenCalledWith(expect.objectContaining({ workItemId: detail.id, ifMatch: detail.etag,
      workItemUpdateRequest: expect.objectContaining({ title: '打印服务调用超时（复现）', priority: 'HIGH', dueDate: detail.dueDate }) }))
    expect(heading().textContent?.trim()).toBe('打印服务调用超时（复现）')
  })
  it('动态页签展示对端工作项的单元格动态，编辑后重新加载', async () => {
    render(); await flushPromises()
    document.querySelectorAll<HTMLElement>('[role="tab"]')[1]!.click(); await flushPromises()
    expect(document.querySelector('.activity-stub')?.textContent).toBe(connection.target.workItemId)
  })
  it('无权访问对端项目时不请求详情，只显示最小只读卡片', async () => {
    const minimal = { ...connection, target: { ...connection.target, canOpen: false } }
    const { wrapper } = render({ connection: minimal }); await flushPromises()
    expect(api.getWorkItem).not.toHaveBeenCalled()
    expect(text()).toContain('你无权访问「Yumpoo 门户」，仅显示连接摘要。')
    expect(wrapper.exists()).toBe(true)
    expect(document.querySelector('[role="tab"]')).toBeNull()
    expect(document.querySelector('button.connected-item-card__tile')).toBeNull()
    expect([...document.querySelectorAll('.connected-item-card__row dt')].map(node => node.textContent?.trim())).toEqual(['工作项类别', '处理人', '状态', '优先级'])
  })
  it('详情返回 403 时退回最小卡片，工作项不可编辑时字段只读', async () => {
    api.getWorkItem.mockRejectedValueOnce(new ResponseError(new Response(JSON.stringify({ code: 'ACCESS_DENIED', message: '无权访问', requestId: 'r', retryable: false,
      fieldErrors: [], details: {} }), { status: 403 })))
    const first = render(); await flushPromises()
    expect(text()).toContain('仅显示连接摘要')
    first.wrapper.unmount()
    api.getWorkItem.mockResolvedValue({ ...detail, capabilities: { ...detail.capabilities, canEditFields: false } })
    document.body.innerHTML = ''
    render(); await flushPromises()
    expect(document.querySelector('button.connected-item-card__tile')).toBeNull()
    expect(heading().classList).not.toContain('is-editable')
  })
  it('更多菜单可打开工作项和确认后解除连接；看板只读时不提供解除', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const { wrapper, context } = render(); await flushPromises()
    wrapper.getComponent(ElDropdown).vm.$emit('command', 'unlink'); await flushPromises()
    expect(context.unlink).toHaveBeenCalledWith(connection)
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    wrapper.unmount()
    const readOnly = render({ readOnly: true }); await flushPromises()
    readOnly.wrapper.getComponent(ElDropdown).vm.$emit('command', 'open'); await flushPromises()
    expect(push).toHaveBeenCalledWith({ path: `/projects/${connection.target.projectId}/overview`, query: { view: 'table', workItemId: connection.target.workItemId } })
    readOnly.wrapper.getComponent(ElDropdown).vm.$emit('command', 'unlink'); await flushPromises()
    expect(readOnly.context.unlink).not.toHaveBeenCalled()
  })
})
