import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MigratedTimeImportOutcome, type MigratedTimeImportRow, type MigratedTimeImportResult } from '@yumpoo/api-client'

const api = vi.hoisted(() => ({ getTeamDashboardOptions: vi.fn(), importMigratedTime: vi.fn() }))
const sheet = vi.hoisted(() => ({ readWorkbook: vi.fn() }))
const push = vi.hoisted(() => vi.fn())
vi.mock('../api/client', () => ({ teamDashboardApi: api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@yumpoo/api-client', async importOriginal => ({ ...await importOriginal<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))
vi.mock('../components/import/spreadsheet', async importOriginal => ({ ...await importOriginal<typeof import('../components/import/spreadsheet')>(), readWorkbook: sheet.readWorkbook }))
vi.mock('element-plus', async importOriginal => ({ ...await importOriginal<typeof import('element-plus')>(), ElMessage: { success: vi.fn(), error: vi.fn() } }))
import TeamHoursImportView from './TeamHoursImportView.vue'

const preview = (): MigratedTimeImportResult => ({ dryRun: true, importedDayCount: 1, failedDayCount: 1, importedMinutes: 450, replacedMinutes: 0, skippedMinutes: 60, skippedRows: [{ index: 2, code: 'WORK_ITEM_NOT_FOUND' }],
  days: [{ userId: 'u1', workDate: new Date('2026-09-01'), minutes: 450, outcome: MigratedTimeImportOutcome.Import, segmentCount: 1, issues: [] },
    { userId: 'u1', workDate: new Date('2026-09-02'), minutes: 600, outcome: MigratedTimeImportOutcome.Error, segmentCount: 0, issues: [{ code: 'NOT_ENOUGH_FREE_TIME', detail: null }] }] })

const mountPage = () => mount(TeamHoursImportView, {
  global: { stubs: { YpModulePage: { template: '<div><slot name="actions" /><slot /></div>' } }, directives: { loading: () => undefined } },
})
async function upload(wrapper: ReturnType<typeof mountPage>) {
  const drop = wrapper.findComponent({ name: 'ImportFileDrop' })
  drop.vm.$emit('file', new File(['x'], '工时.xlsx'))
  await flushPromises()
}
const button = (wrapper: ReturnType<typeof mountPage>, text: string) => wrapper.findAll('button').find(item => item.text() === text)!

beforeEach(() => {
  vi.clearAllMocks()
  api.getTeamDashboardOptions.mockResolvedValue({ timezone: 'Asia/Shanghai', projects: [], members: [
    { userId: 'u1', displayName: '张三', active: true }, { userId: 'u2', displayName: '李四', active: false }] })
  api.importMigratedTime.mockImplementation(async (params: { migratedTimeImportRequest: { dryRun: boolean } }) => params.migratedTimeImportRequest.dryRun ? preview() : { ...preview(), dryRun: false })
})

describe('迁入工时页面', () => {
  it('常驻说明提示排出的时段不代表实际工作时间', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('所以排出的时段不代表实际工作时间')
    wrapper.unmount()
  })

  it('有错误的表格不能进入下一步', async () => {
    sheet.readWorkbook.mockResolvedValue([{ name: '张三', rows: [['责任人', '日期', '标题', '花费工时', '所属项目'], ['张三', '2026-09-01', '开发', 30, '交付']] }])
    const wrapper = mountPage()
    await flushPromises()
    await upload(wrapper)
    expect(wrapper.text()).toContain('不是 0 到 24 的小时数')
    expect(button(wrapper, '下一步').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('匹配在职成员后预览，再按自然日提交并回到成员工时', async () => {
    sheet.readWorkbook.mockResolvedValue([
      { name: '张三', rows: [['责任人', '日期', '标题', '花费工时', '所属项目'],
        ['张三', '2026-09-01', '开发', 7.5, '交付'], [null, '2026-09-02', '开发', 10, '交付'], [null, null, '不存在', 1, '交付']] },
      { name: '李四', rows: [['责任人', '日期', '标题', '花费工时', '所属项目'], ['李四', '2026-09-01', '开发', 8, '交付']] },
    ])
    const wrapper = mountPage()
    await flushPromises()
    await upload(wrapper)
    await button(wrapper, '下一步').trigger('click')
    await button(wrapper, '下一步').trigger('click')
    await flushPromises()
    const dry = api.importMigratedTime.mock.calls[0]![0]
    expect(dry.migratedTimeImportRequest.dryRun).toBe(true)
    expect(dry.migratedTimeImportRequest).not.toHaveProperty('categoryProjectId')
    expect(dry.migratedTimeImportRequest.rows.map((row: MigratedTimeImportRow) => [row.userId, row.workDate.toISOString().slice(0, 10), row.projectName, row.workItemTitle, row.minutes]))
      .toEqual([['u1', '2026-09-01', '交付', '开发', 450], ['u1', '2026-09-02', '交付', '开发', 600], ['u1', '2026-09-02', '交付', '不存在', 60]])
    expect(wrapper.text()).toContain('项目中没有该工作项')
    expect(wrapper.text()).toContain('请先执行工作项导入')
    expect(wrapper.text()).toContain('当天空闲时间不够，未写入')
    await button(wrapper, '迁入').trigger('click')
    await flushPromises()
    const commit = api.importMigratedTime.mock.calls[1]![0]
    expect(commit.migratedTimeImportRequest.dryRun).toBe(false)
    expect(commit.xXSRFTOKEN).toBe('csrf')
    expect(commit.idempotencyKey).not.toBe(dry.idempotencyKey)
    expect(push).toHaveBeenCalledWith({ name: 'team', params: { view: 'timesheet' } })
    wrapper.unmount()
  })
})
