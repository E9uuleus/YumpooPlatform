import { ErrorCode, type WorkItemDetail } from '@yumpoo/api-client'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkItemDescription from './WorkItemDescription.vue'

const api = vi.hoisted(() => ({ patch: vi.fn(), get: vi.fn(), upload: vi.fn() }))

vi.mock('../../api/client', () => ({
  workItemsApi: { patchWorkItemDescription: api.patch, getWorkItem: api.get },
}))
vi.mock('./attachmentUpload', async importOriginal => ({
  ...await importOriginal<typeof import('./attachmentUpload')>(),
  uploadAttachment: api.upload,
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))
vi.mock('../../api/problems', async importOriginal => ({
  ...await importOriginal<typeof import('../../api/problems')>(),
  toApiProblem: async (reason: unknown) => reason,
}))

const workItemId = '39000000-0000-4000-8000-000000000001'
const attachmentId = '39000000-0000-4000-8000-000000000002'
const imageSource = `/api/v1/attachments/${attachmentId}/content`
type Handle = { editor: import('@tiptap/core').Editor; hasDraft: boolean; startEdit: () => Promise<void> }

function detail(patch: Partial<WorkItemDetail> = {}): WorkItemDetail {
  return { id: workItemId, description: '<p>新描述</p>', etag: '"8"', rowVersion: 8, ...patch } as WorkItemDetail
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

function mountDescription(props: Partial<{ description: string | null; canEdit: boolean; etag: string }> = {}) {
  return mount(WorkItemDescription, {
    props: { workItemId, description: '<p>原始描述</p>', etag: '"7"', canEdit: true, ...props },
    attachTo: document.body,
  })
}

async function edit(wrapper: VueWrapper, html: string): Promise<Handle> {
  const handle = wrapper.vm as unknown as Handle
  await handle.startEdit()
  handle.editor.commands.setContent(html, { emitUpdate: true })
  await flushPromises()
  return handle
}

function saveButton(wrapper: VueWrapper) {
  return wrapper.findAll('button').find(button => button.text() === '保存')!
}

function chooseFiles(wrapper: VueWrapper, files: File[]) {
  const input = wrapper.get<HTMLInputElement>('input[type=file]')
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  return input.trigger('change')
}

describe('WorkItemDescription', () => {
  let wrapper: VueWrapper | undefined
  beforeEach(() => {
    vi.clearAllMocks()
    api.patch.mockResolvedValue(detail())
  })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined })

  it('查看态渲染净化后的富文本，只读与空描述有明确状态', async () => {
    wrapper = mountDescription({ description: `<h2>背景</h2><img src="${imageSource}" alt="截图">` })
    expect(wrapper.get('.work-item-description__body h2').text()).toBe('背景')
    expect(wrapper.find('.work-item-description__edit').exists()).toBe(true)
    await wrapper.get('.work-item-description__body img').trigger('click')
    expect(wrapper.findComponent({ name: 'ElImageViewer' }).props('urlList')).toEqual([imageSource])
    wrapper.unmount()

    wrapper = mountDescription({ description: null })
    expect(wrapper.get('.work-item-description__empty').text()).toContain('添加描述')
    wrapper.unmount()

    wrapper = mountDescription({ description: null, canEdit: false })
    expect(wrapper.text()).toContain('暂无描述')
    expect(wrapper.find('.work-item-description__empty').exists()).toBe(false)
    expect(wrapper.find('.work-item-description__edit').exists()).toBe(false)
  })

  it('编辑器不提供 @ 提及，保存携带强 ETag 与幂等键并回到查看态', async () => {
    wrapper = mountDescription()
    await wrapper.get('.work-item-description__edit').trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-label="提及项目成员"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="插入图片"]').exists()).toBe(true)
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()

    const handle = await edit(wrapper, '<p>新描述</p>')
    expect(handle.hasDraft).toBe(true)
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    expect(api.patch).toHaveBeenCalledWith({
      workItemId, xXSRFTOKEN: 'csrf-token', ifMatch: '"7"', idempotencyKey: expect.any(String),
      workItemDescriptionPatchRequest: { description: '<p>新描述</p>' },
    })
    expect(wrapper.emitted('updated')?.[0]).toEqual([detail()])
    expect(handle.hasDraft).toBe(false)
    expect(wrapper.find('.work-item-description__body').exists()).toBe(true)
  })

  it('清空全部内容时保存为 null', async () => {
    wrapper = mountDescription()
    await edit(wrapper, '<p></p>')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(api.patch.mock.calls[0]![0].workItemDescriptionPatchRequest).toEqual({ description: null })
  })

  it('版本冲突保留草稿和幂等键，只能显式载入最新', async () => {
    api.patch.mockRejectedValueOnce({ kind: 'response', status: 412, error: { code: ErrorCode.VersionConflict, message: '版本冲突' } })
    wrapper = mountDescription()
    const handle = await edit(wrapper, '<p>我的草稿</p>')
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    expect(handle.hasDraft).toBe(true)
    expect(handle.editor.getHTML()).toBe('<p>我的草稿</p>')
    expect(wrapper.text()).toContain('草稿仍保留')
    api.get.mockResolvedValueOnce(detail({ description: '<p>别人写的</p>', etag: '"9"' }))
    await wrapper.findAll('button').find(button => button.text() === '放弃草稿并载入最新')!.trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith({ workItemId })
    expect(wrapper.emitted('updated')?.[0]?.[0]).toMatchObject({ etag: '"9"' })
    expect(handle.hasDraft).toBe(false)
    expect(api.patch).toHaveBeenCalledTimes(1)
  })

  it('图片先显示上传占位，扫描通过后替换为同源附件图片', async () => {
    const upload = deferred<{ id: string; originalFileName: string }>()
    api.upload.mockImplementationOnce(({ onPhase }: { onPhase: (phase: string) => void }) => { onPhase('scanning'); return upload.promise })
    wrapper = mountDescription()
    const handle = await edit(wrapper, '<p>复现步骤</p>')
    await chooseFiles(wrapper, [new File(['png'], '遮挡.png', { type: 'image/png' })])
    await flushPromises()

    expect(api.upload).toHaveBeenCalledWith(expect.objectContaining({ ownerType: 'WORK_ITEM', ownerId: workItemId }))
    expect(wrapper.get('[data-image-upload]').text()).toContain('安全扫描中')
    expect(wrapper.text()).toContain('1 张图片处理中')
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()

    upload.resolve({ id: attachmentId, originalFileName: '遮挡.png' })
    await flushPromises()
    expect(wrapper.find('[data-image-upload]').exists()).toBe(false)
    expect(handle.editor.getHTML()).toContain(`<img src="${imageSource}" alt="遮挡.png">`)
    expect(saveButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('上传失败可移除占位，不支持的文件直接拒绝', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: () => {} }) as never)
    api.upload.mockRejectedValueOnce(new Error('文件未通过安全检查'))
    wrapper = mountDescription()
    const handle = await edit(wrapper, '<p>正文</p>')
    await chooseFiles(wrapper, [new File(['x'], 'a.webp', { type: 'image/webp' }), new File(['png'], 'b.png', { type: 'image/png' })])
    await flushPromises()

    expect(warning).toHaveBeenCalledWith(expect.stringContaining('仅支持 PNG、JPG、GIF'))
    expect(api.upload).toHaveBeenCalledTimes(1)
    const placeholder = wrapper.get('[data-image-upload]')
    expect(placeholder.text()).toContain('文件未通过安全检查')
    await placeholder.get('button').trigger('click')
    expect(wrapper.find('[data-image-upload]').exists()).toBe(false)
    expect(handle.editor.getHTML()).toMatch(/^<p>正文<\/p>(<p><\/p>)?$/)
  })

  it('取消编辑前确认放弃草稿并中止上传', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm' as never)
    let signal: AbortSignal | undefined
    api.upload.mockImplementationOnce((options: { signal: AbortSignal }) => { signal = options.signal; return new Promise(() => {}) })
    wrapper = mountDescription()
    const handle = await edit(wrapper, '<p>草稿</p>')
    await chooseFiles(wrapper, [new File(['png'], 'c.png', { type: 'image/png' })])
    const cancel = wrapper.findAll('button').find(button => button.text() === '取消' && !button.classes('image-upload__remove'))!

    await cancel.trigger('click')
    await flushPromises()
    expect(handle.hasDraft).toBe(true)
    await cancel.trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(signal?.aborted).toBe(true)
    expect(handle.hasDraft).toBe(false)
    expect(wrapper.find('.work-item-description__body').exists()).toBe(true)
  })
})
