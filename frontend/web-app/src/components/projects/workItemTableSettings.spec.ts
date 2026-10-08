import {
  WorkItemColoringColumn as Column,
  WorkItemColoringOperator as Operator,
  WorkItemColoringTarget as Target,
  WorkItemLabelColorToken as Color,
  type ProjectWorkItemListItem,
  type WorkItemColoringRule,
} from '@yumpoo/api-client'
import { describe, expect, it } from 'vitest'
import { coloringBackground, evaluateWorkItemColoring, resolveWorkItemCreateDefaults } from './workItemTableSettings'

const TODAY = '2026-10-07'
const item = (patch: Partial<ProjectWorkItemListItem> = {}) => ({
  id: 'item-1', title: '修复登录卡住', statusCode: 'STUCK', priority: null, contentId: 'content-1',
  assigneeUserId: 'user-1', assigneeDisplayName: '甲', dueDate: new Date('2026-10-06T00:00:00Z'), ...patch,
}) as ProjectWorkItemListItem
const rule = (patch: Partial<WorkItemColoringRule>): WorkItemColoringRule => ({
  id: crypto.randomUUID(), target: Target.Row, colorToken: Color.SofiaPink, column: Column.Status,
  operator: Operator.Is, values: ['STUCK'], ...patch,
})

describe('条件着色', () => {
  it('行与单元格分别取第一条命中规则，未完成规则与色块列单元格规则不生效', () => {
    const result = evaluateWorkItemColoring([
      rule({ column: null, operator: null, values: [] }),
      rule({ target: Target.Cell }),
      rule({ colorToken: Color.Sky }),
      rule({ colorToken: Color.Teal }),
      rule({ target: Target.Cell, column: Column.Title, operator: Operator.Contains, values: ['登录'], colorToken: Color.EggYolk }),
      rule({ target: Target.Cell, column: Column.Assignee, operator: Operator.IsEmpty, values: [], colorToken: Color.Berry }),
    ], item(), TODAY)
    expect(result?.row).toBe(coloringBackground(Color.Sky))
    expect(result?.cells).toEqual({ title: coloringBackground(Color.EggYolk) })
  })

  it.each([[Operator.Past, true], [Operator.Today, false], [Operator.IsEmpty, false]])('截止日期 %s 按企业时区今天比较', (operator, matched) => {
    expect(Boolean(evaluateWorkItemColoring([rule({ column: Column.DueDate, operator, values: [] })], item(), TODAY))).toBe(matched)
  })

  it('早于、晚于需要合法日期', () => {
    expect(evaluateWorkItemColoring([rule({ column: Column.DueDate, operator: Operator.Before, values: [TODAY] })], item(), TODAY)).toBeDefined()
    expect(evaluateWorkItemColoring([rule({ column: Column.DueDate, operator: Operator.After, values: ['bad'] })], item(), TODAY)).toBeUndefined()
  })
})

describe('新建默认值', () => {
  it('忽略失效值与初始状态，截止日期按今天偏移', () => {
    const resolved = resolveWorkItemCreateDefaults({
      assigneeUserIds: new Set(['gone', 'user-2']), statusCode: 'NOT_STARTED', priority: 'OLD', contentId: 'content-2', dueDateOffsetDays: 3,
    }, {
      memberIds: new Set(['user-2']), statusCodes: new Set(['NOT_STARTED']), priorityCodes: new Set(['HIGH']),
      contentIds: new Set(['content-2']), today: TODAY,
    })
    expect(resolved).toEqual({
      contentId: 'content-2', statusCode: null, count: 3,
      fields: { assigneeUserIds: ['user-2'], assigneeUserId: 'user-2', dueDate: new Date('2026-10-10T00:00:00Z') },
    })
  })
})
