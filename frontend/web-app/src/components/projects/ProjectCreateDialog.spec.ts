import { ErrorCode, ResponseError, type Project } from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ElDialog, ElInput, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectCreateDialog from './ProjectCreateDialog.vue'

const api = vi.hoisted(() => ({ createProject: vi.fn() }))
vi.mock('../../api/client', () => ({ projectsApi: api }))
vi.mock('../../composables/useSession', () => ({ useSession: () => ({ authentication: { value: { user: { id: 'user-1', displayName: '张三' } } } }) }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))
enableAutoUnmount(afterEach)
beforeEach(() => { document.body.innerHTML = ''; api.createProject.mockReset(); vi.restoreAllMocks() })
function render() { return mount(ProjectCreateDialog, { props: { modelValue: true }, attachTo: document.body }) }
type DialogVm = { submit: () => Promise<void>; close: (done?: () => void) => Promise<void> }

describe('创建项目弹窗', () => {
  it('固定 800 档位、禁用遮罩关闭，预览三个类别、五个状态与四个优先级', async () => {
    const wrapper = render(); await flushPromises()
    await flushPromises()
    expect(wrapper.findComponent(ElDialog).props()).toMatchObject({ width: 'min(800px, calc(100vw - 32px))', top: '10vh', closeOnClickModal: false })
    expect(document.body.querySelectorAll('.project-create-dialog__row')).toHaveLength(4)
    expect(document.body.querySelectorAll('.project-create-dialog__labels span')).toHaveLength(12)
    expect(document.body.textContent).toContain('张三')
    expect(document.body.textContent).toContain('项目编码创建后由系统生成')
    expect(document.body.textContent).toContain('未命名项目')
    expect(document.body.textContent).toContain('暂无描述')
    expect(wrapper.findAllComponents(ElInput)).toHaveLength(2)
    expect(document.body.querySelector('input')?.getAttribute('maxlength')).toBe('80')
    expect(document.body.querySelector('textarea')?.getAttribute('maxlength')).toBe('500')
    await wrapper.findAllComponents(ElInput)[0]!.setValue(' 新项目 ')
    await wrapper.findAllComponents(ElInput)[1]!.setValue(' 项目目标 ')
    expect(document.body.querySelector('.project-create-dialog__initial-avatar')?.textContent).toBe('新')
    expect(document.body.querySelector('.project-create-dialog__identity-text')?.textContent).toContain('新项目')
    expect(document.body.querySelector('.project-create-dialog__identity-text')?.textContent).toContain('项目目标')
  })
  it('空白名称不可提交，去首尾空白后只发送名称和描述，成功通知父页面', async () => {
    const wrapper = render(); await flushPromises()
    const vm = wrapper.vm as unknown as DialogVm
    await wrapper.findComponent(ElInput).setValue('   ')
    await vm.submit()
    expect(api.createProject).not.toHaveBeenCalled()
    const project = { id: 'project-1', code: 'P001' } as Project
    api.createProject.mockResolvedValue(project)
    await wrapper.findComponent(ElInput).setValue(' 新项目 ')
    await vm.submit()
    expect(api.createProject).toHaveBeenCalledWith(expect.objectContaining({ xXSRFTOKEN: 'csrf', projectCreateRequest: { name: '新项目', description: null } }))
    expect(wrapper.emitted('created')).toEqual([[project]])
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })
  it('失败保留输入和幂等键；请求内容变化后才换键', async () => {
    const wrapper = render(); await flushPromises(); const vm = wrapper.vm as unknown as DialogVm
    api.createProject.mockRejectedValue(new TypeError('offline'))
    await wrapper.findComponent(ElInput).setValue('项目')
    await vm.submit()
    await vm.submit()
    const first = api.createProject.mock.calls[0]![0].idempotencyKey
    expect(api.createProject.mock.calls[1]![0].idempotencyKey).toBe(first)
    expect((document.body.querySelector('input') as HTMLInputElement).value).toBe('项目')
    await wrapper.findComponent(ElInput).setValue('另一项目')
    await vm.submit()
    expect(api.createProject.mock.calls[2]![0].idempotencyKey).not.toBe(first)
  })
  it('422 同时呈现字段错误和统一错误提示', async () => {
    const wrapper = render(); await flushPromises()
    api.createProject.mockRejectedValue(new ResponseError(new Response(JSON.stringify({ code: ErrorCode.ValidationFailed, message: '名称校验失败', requestId: 'up2-field', retryable: false, fieldErrors: [{ field: 'name', code: 'INVALID_VALUE', message: '名称不符合要求' }], details: {} }), { status: 422 })))
    await wrapper.findComponent(ElInput).setValue('项目')
    await (wrapper.vm as unknown as DialogVm).submit()
    await flushPromises()
    expect(document.body.textContent).toContain('名称校验失败')
    await vi.waitFor(() => expect(document.body.querySelector('.el-form-item__error')?.textContent).toBe('名称不符合要求'))
  })
  it('脏内容关闭需确认，提交期间禁用关闭与重复提交', async () => {
    const wrapper = render(); await flushPromises(); const vm = wrapper.vm as unknown as DialogVm
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    await wrapper.findComponent(ElInput).setValue('项目')
    const done = vi.fn()
    await vm.close(done)
    expect(confirm).toHaveBeenCalledOnce()
    expect(done).not.toHaveBeenCalled()
    let resolve!: (project: Project) => void
    api.createProject.mockImplementation(() => new Promise<Project>(accept => { resolve = accept }))
    const pending = vm.submit()
    await flushPromises()
    expect(wrapper.findComponent(ElDialog).props()).toMatchObject({ showClose: false, closeOnPressEscape: false })
    await vm.close(done)
    await vm.submit()
    expect(done).not.toHaveBeenCalled()
    expect(api.createProject).toHaveBeenCalledOnce()
    resolve({ id: 'created' } as Project)
    await pending
  })
  it('打开聚焦名称，关闭恢复创建入口焦点', async () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const wrapper = render(); await flushPromises()
    wrapper.findComponent(ElDialog).vm.$emit('open-auto-focus')
    await flushPromises()
    expect(document.activeElement).toBe(document.body.querySelector('.project-create-dialog input'))
    wrapper.findComponent(ElDialog).vm.$emit('close-auto-focus')
    expect(document.activeElement).toBe(opener)
  })
})
