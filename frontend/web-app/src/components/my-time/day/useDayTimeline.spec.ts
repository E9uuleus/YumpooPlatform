import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { TimeSessionFillMode } from '@yumpoo/api-client'
import { useDayTimeline } from './useDayTimeline'
import { atMinute, type DayBlock } from './dayModel'

const api = vi.hoisted(() => ({ listMyTimeSessions: vi.fn(), fillMyTimeSessions: vi.fn(), getMyTimesheet: vi.fn(), editMyTimeSession: vi.fn(),
  deleteMyTimeSession: vi.fn(), reassignMyTimeSession: vi.fn() }))
vi.mock('../../../api/client', () => ({ timeTrackingApi: api, myWorkApi: api }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), warning: vi.fn(), info: vi.fn(), success: vi.fn() } }))
vi.mock('../../../composables/useTimeTracker', async () => {
  const { ref } = await import('vue')
  return {
    useTimeTracker: () => ({ now: ref(Date.parse('2026-10-09T00:00:00Z')),
      preferences: ref({ timerEnabled: true, workdayStartMinute: 540, workdayEndMinute: 1080 }) }),
    onTimeTrackingChanged: () => () => undefined,
    timerMutation: (_identity: string, call: (key: string, csrf: string) => Promise<unknown>) => call('key', 'csrf'),
  }
})

const date = '2026-10-08', zone = 'Asia/Shanghai'
const block: DayBlock = { id: 'record', workItemId: 'item', timeCategory: null, subjectKey: 'item', projectId: 'project', title: '工作项',
  itemNo: null, projectName: null, colorToken: null,
  start: atMinute(date, 540, zone), end: atMinute(date, 600, zone), canEdit: true, etag: '"1"' }
let timeline: ReturnType<typeof useDayTimeline>
let wrapper: ReturnType<typeof mount>

beforeEach(async () => {
  vi.clearAllMocks()
  api.listMyTimeSessions.mockResolvedValue({ items: [] })
  api.getMyTimesheet.mockResolvedValue({ days: [] })
  wrapper = mount(defineComponent({ setup() {
    timeline = useDayTimeline(ref(date), ref(zone))
    return () => null
  } }))
  await flushPromises()
})
afterEach(() => wrapper.unmount())

describe('非项目分类', () => {
  it('补录分类携带 timeCategory 和关联项目，编辑与删除走本人记录接口', async () => {
    api.fillMyTimeSessions.mockResolvedValue({ createdCount: 1, changedCount: 0, deletedCount: 0, skippedDurationMs: 0 })
    await timeline.create(TimeSessionFillMode.Gaps, ['category:MEETING:project', 'item'], [{ start: block.start, end: block.end! }])
    expect(api.fillMyTimeSessions.mock.calls[0]![0].timeSessionFillCommand.entries).toEqual([
      { timeCategory: 'MEETING', categoryProjectId: 'project', startedAt: new Date(block.start), stoppedAt: new Date(block.end!) },
      { workItemId: 'item', startedAt: new Date(block.start), stoppedAt: new Date(block.end!) }])
    api.editMyTimeSession.mockResolvedValue({})
    api.deleteMyTimeSession.mockResolvedValue({})
    const meeting = { ...block, workItemId: null, timeCategory: 'MEETING' as DayBlock['timeCategory'], subjectKey: 'category:MEETING' }
    expect(await timeline.edit(meeting, { start: block.start, end: block.end! + 60_000 })).toBe(true)
    expect(api.editMyTimeSession).toHaveBeenCalledWith(expect.objectContaining({ sessionId: 'record', ifMatch: '"1"' }))
    expect(await timeline.remove(meeting)).toBe(true)
    expect(api.deleteMyTimeSession).toHaveBeenCalledWith(expect.objectContaining({ sessionId: 'record' }))
  })

  it('改归属可以转到分类，未关联项目的分类记录只显示分类名', async () => {
    api.reassignMyTimeSession.mockResolvedValue({ createdCount: 1, changedCount: 0, deletedCount: 1, skippedDurationMs: 0 })
    expect(await timeline.reassign(block, 'category:LEARNING')).toBe(true)
    expect(api.reassignMyTimeSession).toHaveBeenCalledWith(expect.objectContaining({ timeSessionReassignCommand: { timeCategory: 'LEARNING' } }))
    api.listMyTimeSessions.mockResolvedValue({ items: [{ projectName: null, itemNo: null, title: null, contentColorToken: null,
      session: { id: 's', workItemId: null, projectId: null, timeCategory: 'BUSINESS_TRIP', startedAt: new Date(block.start),
        stoppedAt: new Date(block.end!), canEdit: true, etag: '"0"' } }] })
    await timeline.load()
    expect(timeline.blocks.value[0]).toMatchObject({ title: '出差', projectName: null, subjectKey: 'category:BUSINESS_TRIP', colorToken: 'RIVER' })
    expect(timeline.totals.value.map(total => total.key)).toEqual(['category:BUSINESS_TRIP'])
  })
})

describe('我的一天写入反馈', () => {
  it.each([
    ['仅删除完全重叠记录', { createdCount: 0, changedCount: 0, deletedCount: 1 }],
    ['裁剪并拆分部分重叠记录', { createdCount: 1, changedCount: 1, deletedCount: 0 }],
  ])('独占时段%s时报告调整成功，不误报补录跳过', async (_name, counts) => {
    api.fillMyTimeSessions.mockResolvedValue({ ...counts, skippedDurationMs: 3_600_000 })
    expect(await timeline.exclusive(block)).toBe(true)
    expect(api.fillMyTimeSessions).toHaveBeenCalledWith({ idempotencyKey: 'key', xXSRFTOKEN: 'csrf',
      timeSessionFillCommand: { mode: TimeSessionFillMode.Replace, entries: [{ workItemId: 'item',
        startedAt: new Date(block.start), stoppedAt: new Date(block.end!) }] } })
    expect(ElMessage.success).toHaveBeenCalledWith('已调整可编辑的重叠记录。')
    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(ElMessage.info).not.toHaveBeenCalled()
  })

  it('独占未改变任何记录时说明没有可调整的重叠记录', async () => {
    api.fillMyTimeSessions.mockResolvedValue({ createdCount: 0, changedCount: 0, deletedCount: 0, skippedDurationMs: 3_600_000 })
    expect(await timeline.exclusive(block)).toBe(true)
    expect(ElMessage.info).toHaveBeenCalledWith('没有可调整的重叠记录。')
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(ElMessage.warning).not.toHaveBeenCalled()
  })

  it('覆盖补录仅删除其他记录时也视为有效变化', async () => {
    api.fillMyTimeSessions.mockResolvedValue({ createdCount: 0, changedCount: 0, deletedCount: 1, skippedDurationMs: 3_600_000 })
    expect(await timeline.create(TimeSessionFillMode.Replace, ['item'], [{ start: block.start, end: block.end! }])).toMatchObject({ deletedCount: 1 })
    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(ElMessage.info).toHaveBeenCalledWith('已更新记录；所选工作项已有的 1h 记录已保留。')
  })

  it('仅补空白没有任何变化时保留没有新增的提示', async () => {
    api.fillMyTimeSessions.mockResolvedValue({ createdCount: 0, changedCount: 0, deletedCount: 0, skippedDurationMs: 3_600_000 })
    expect(await timeline.create(TimeSessionFillMode.Gaps, ['item'], [{ start: block.start, end: block.end! }])).toMatchObject({ createdCount: 0 })
    expect(ElMessage.warning).toHaveBeenCalledWith('这段时间已全部有记录，没有新增。')
    expect(ElMessage.success).not.toHaveBeenCalled()
  })
})
