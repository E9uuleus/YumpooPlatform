import { describe, expect, it } from 'vitest'
import { WorkItemDetailFromJSON } from '../src/generated/models/WorkItemDetail.js'
import { WorkItemSummaryFromJSON } from '../src/generated/models/WorkItemSummary.js'
import { ProjectWorkItemListItemFromJSON } from '../src/generated/models/ProjectWorkItemListItem.js'
import { WorkItemDueDatePatchRequestFromJSON, WorkItemDueDatePatchRequestToJSON } from '../src/generated/models/WorkItemDueDatePatchRequest.js'
import { WorkItemCreateRequestFromJSON, WorkItemCreateRequestToJSON } from '../src/generated/models/WorkItemCreateRequest.js'
import { WorkItemSubitemCreateRequestFromJSON, WorkItemSubitemCreateRequestToJSON } from '../src/generated/models/WorkItemSubitemCreateRequest.js'
import { WorkItemUpdateRequestFromJSON, WorkItemUpdateRequestToJSON } from '../src/generated/models/WorkItemUpdateRequest.js'

describe('截止日期兼容契约', () => {
  for (const decode of [WorkItemDetailFromJSON, WorkItemSummaryFromJSON, ProjectWorkItemListItemFromJSON]) {
    it(`${decode.name} 兼容旧响应，区分缺失与 null，并解析完成时间`, () => {
      const legacy = decode({ dueDate: '2026-09-03' })
      expect(legacy).not.toHaveProperty('dueTime')
      expect(legacy).not.toHaveProperty('completedAt')
      expect(legacy.dueDate?.toISOString()).toBe('2026-09-03T00:00:00.000Z')
      const cleared = decode({ dueDate: null, dueTime: null, completedAt: null })
      expect(cleared.dueTime).toBeNull()
      expect(cleared.completedAt).toBeNull()
      const timed = decode({ dueDate: '2026-09-03', dueTime: '18:05', completedAt: '2026-09-03T10:00:00Z' })
      expect(timed.dueTime).toBe('18:05')
      expect(timed.completedAt?.toISOString()).toBe('2026-09-03T10:00:00.000Z')
    })
  }
  for (const [decode, encode] of [
    [WorkItemDueDatePatchRequestFromJSON, WorkItemDueDatePatchRequestToJSON],
    [WorkItemCreateRequestFromJSON, WorkItemCreateRequestToJSON],
    [WorkItemSubitemCreateRequestFromJSON, WorkItemSubitemCreateRequestToJSON],
    [WorkItemUpdateRequestFromJSON, WorkItemUpdateRequestToJSON],
  ] as const) {
    it(`${decode.name} 保持时分省略、清除和分钟精度的 wire 语义`, () => {
      // All request decoders accept the shared fixture; extra fields do not affect the deadline.
      const fixture = { contentId: 'content', title: '截止日期', dueDate: '2026-09-03' }
      const absent = JSON.parse(JSON.stringify(encode(decode(fixture))))
      expect(absent).not.toHaveProperty('dueTime')
      expect(absent.dueDate).toBe('2026-09-03')
      expect(JSON.parse(JSON.stringify(encode(decode({ ...fixture, dueTime: null }))))).toHaveProperty('dueTime', null)
      expect(JSON.parse(JSON.stringify(encode(decode({ ...fixture, dueTime: '00:00' }))))).toHaveProperty('dueTime', '00:00')
    })
  }
})
