import { describe, expect, it } from 'vitest'
import { TimerCandidateFromJSON, TimerCandidateToJSON } from '../src/generated/models/TimerCandidate.js'

const legacy = {
  workItemId: 'w1', projectId: 'p1', projectName: '产品演示', itemNo: 'P001-1', title: '设计评审', contentName: '需求',
  statusCategory: 'TODO', completed: false, assignedToMe: true, lastTrackedAt: null, ownDurationMs: 0,
}

describe('计时候选实际状态', () => {
  it('保留自定义状态名称与颜色', () => {
    const value = { ...legacy, statusCode: 'PLANNED', statusName: '已排期', statusColorToken: 'EGG_YOLK' }
    expect(TimerCandidateToJSON(TimerCandidateFromJSON(value))).toMatchObject(value)
  })

  it('兼容缺少状态展示字段的旧响应，不产生显式 undefined 属性', () => {
    const parsed = TimerCandidateFromJSON(legacy)
    expect(parsed).toEqual(legacy)
    for (const field of ['statusCode', 'statusName', 'statusColorToken']) expect(parsed).not.toHaveProperty(field)
  })
})
