import type { Content, ProjectMember, WorkItemImportResult, WorkItemPriorityLabel, WorkItemStatusLabel } from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElSelect } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ImportFileDrop from '../../import/ImportFileDrop.vue'
import WorkItemImportDialog from './WorkItemImportDialog.vue'

const api = vi.hoisted(() => ({ importWorkItems: vi.fn() }))
vi.mock('../../../api/client', () => ({ workItemsApi: api }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))
enableAutoUnmount(afterEach)

const member = (userId: string, displayName: string): ProjectMember => ({
  userId, displayName, membershipStatus: 'ACTIVE', employmentStatus: 'ACTIVE', accountStatus: 'ENABLED',
}) as unknown as ProjectMember
const status = (code: string, displayName: string, sortOrder: number): WorkItemStatusLabel =>
  ({ code, displayName, sortOrder, active: true }) as WorkItemStatusLabel
const priority = (code: string, displayName: string, sortOrder: number): WorkItemPriorityLabel =>
  ({ code, displayName, sortOrder, active: true }) as WorkItemPriorityLabel

const props = {
  modelValue: true,
  projectId: 'project-1',
  contents: [{ id: 'c-task', code: 'TASK', name: '任务', active: true }] as Content[],
  statuses: [status('NOT_STARTED', '未开始', 10), status('DONE', '已完成', 40)],
  priorities: [priority('HIGH', 'High', 30)],
  members: [member('u-ma', '马郅昱'), member('u-1', '李刚'), member('u-2', '李刚')],
  defaultContentId: 'c-task',
}

const CSV = '标题,优先级,状态,处理人\n甲,High,已完成,马郅昱;\n乙,,规划中,李刚;\n'

const result = (overrides: Partial<WorkItemImportResult> = {}): WorkItemImportResult => ({
  dryRun: true, createdCount: 2, updatedCount: 0, skippedCount: 0, failedCount: 0, rows: [], ...overrides,
})

async function openAtMapping() {
  const wrapper = mount(WorkItemImportDialog, { props, attachTo: document.body })
  await flushPromises()
  wrapper.findComponent(ImportFileDrop).vm.$emit('file', new File([CSV], '导出.csv'))
  await vi.waitFor(() => expect(document.body.textContent).toContain('已读取「导出.csv」'))
  return wrapper
}

const sameNameSelect = (wrapper: VueWrapper) => wrapper.findAllComponents(ElSelect)
  .find(item => item.element.closest('.wi-values__item')?.textContent?.includes('李刚'))!

const buttons = (): HTMLButtonElement[] => [...document.body.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button')]
const button = (label: string): HTMLButtonElement => buttons().find(item => item.textContent?.replace(/\s/g, '').startsWith(label.replace(/\s/g, '')))!

beforeEach(() => {
  document.body.innerHTML = ''
  api.importWorkItems.mockReset()
})

describe('导入工作项弹窗', () => {
  it('替换文件读取失败后不允许继续导入旧文件', async () => {
    const wrapper = await openAtMapping()
    expect(button('下一步').disabled).toBe(false)
    wrapper.findComponent(ImportFileDrop).vm.$emit('file', new File(['标题\n'], '无数据.csv'))
    await vi.waitFor(() => expect(document.body.textContent).toContain('文件里没有数据行'))
    expect(button('下一步').disabled).toBe(true)
    expect(document.body.textContent).not.toContain('已读取「导出.csv」')
  })

  it('重新打开弹窗后忽略上次尚未完成的文件读取', async () => {
    const wrapper = mount(WorkItemImportDialog, { props, attachTo: document.body })
    await flushPromises()
    let finishRead!: (buffer: ArrayBuffer) => void
    const file = new File([CSV], '旧文件.csv')
    vi.spyOn(file, 'arrayBuffer').mockImplementation(() => new Promise(resolve => { finishRead = resolve }))
    wrapper.findComponent(ImportFileDrop).vm.$emit('file', file)
    await flushPromises()
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    finishRead(new TextEncoder().encode(CSV).buffer)
    await flushPromises()
    expect(button('下一步').disabled).toBe(true)
    expect(document.body.textContent).not.toContain('已读取「旧文件.csv」')
  })

  it('读取文件后进入映射，同名处理人选定前不能进入下一步', async () => {
    const wrapper = await openAtMapping()
    expect(document.body.textContent).toContain('2 行数据，4 列')
    await button('下一步').click()
    await flushPromises()
    expect(document.body.textContent).toContain('工时不在这里导入')
    expect(document.body.textContent).toContain('已映射 4 · 不导入 0')
    expect(button('下一步').disabled).toBe(true)

    expect(document.body.querySelector('.is-unresolved')?.closest('li')?.textContent).toContain('李刚')
    expect(wrapper.exists()).toBe(true)
  })

  it('进入预览自动试运行，切换处理方式再试运行，导入时带幂等键并通知父页面', async () => {
    const wrapper = await openAtMapping()
    await button('下一步').click()
    await flushPromises()
    // 选定同名成员，使「下一步」可用
    sameNameSelect(wrapper).vm.$emit('update:modelValue', 'u-2')
    await flushPromises()
    expect(button('下一步').disabled).toBe(false)

    api.importWorkItems.mockResolvedValue(result())
    await button('下一步').click()
    await flushPromises()
    expect(api.importWorkItems).toHaveBeenCalledTimes(1)
    const first = api.importWorkItems.mock.calls[0]![0]
    expect(first).toMatchObject({ projectId: 'project-1', xXSRFTOKEN: 'csrf' })
    expect(first.idempotencyKey).toEqual(expect.any(String))
    expect(first.workItemImportRequest).toMatchObject({
      dryRun: true, duplicateMode: 'SKIP', fields: ['STATUS', 'PRIORITY', 'ASSIGNEES'],
    })
    expect(first.workItemImportRequest.rows).toEqual([
      expect.objectContaining({ rowNumber: 2, title: '甲', statusCode: 'DONE', priority: 'HIGH', assigneeUserIds: ['u-ma'], contentId: 'c-task' }),
      expect.objectContaining({ rowNumber: 3, title: '乙', statusCode: 'NOT_STARTED', priority: null, assigneeUserIds: ['u-2'] }),
    ])
    expect(button('导入 2 项').disabled).toBe(false)

    await document.body.querySelectorAll<HTMLButtonElement>('.yp-choice-card')[2]!.click()
    await flushPromises()
    expect(api.importWorkItems).toHaveBeenCalledTimes(2)
    expect(api.importWorkItems.mock.calls[1]![0].workItemImportRequest).toMatchObject({ dryRun: true, duplicateMode: 'UPDATE' })

    api.importWorkItems.mockResolvedValue(result({ dryRun: false }))
    await button('导入 2 项').click()
    await flushPromises()
    const commit = api.importWorkItems.mock.calls[2]![0]
    expect(commit.workItemImportRequest).toMatchObject({ dryRun: false, duplicateMode: 'UPDATE' })
    expect(wrapper.emitted('imported')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })

  it('预览里的问题行按中文说明展示，没有可导入项时不能提交', async () => {
    const wrapper = await openAtMapping()
    await button('下一步').click()
    await flushPromises()
    sameNameSelect(wrapper).vm.$emit('update:modelValue', 'u-1')
    await flushPromises()
    api.importWorkItems.mockResolvedValue(result({
      createdCount: 0, failedCount: 1, skippedCount: 1,
      rows: [
        { rowNumber: 2, outcome: 'SKIP', issues: [] },
        { rowNumber: 3, outcome: 'ERROR', issues: [{ code: 'MATCH_AMBIGUOUS', detail: null }] },
      ] as WorkItemImportResult['rows'],
    }))
    await button('下一步').click()
    await flushPromises()
    expect(button('导入 0 项').disabled).toBe(true)
    await [...document.body.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('问题列表'))!.click()
    await flushPromises()
    expect(document.body.querySelector('.wi-issues')?.textContent).toContain('项目中有多个同名工作项，无法确定更新哪一个')
    expect(document.body.querySelector('.wi-issues')?.textContent).toContain('第 3 行')
  })
})
