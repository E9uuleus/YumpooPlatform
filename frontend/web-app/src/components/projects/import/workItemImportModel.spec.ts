import { WorkItemImportDuplicateMode, WorkItemImportField } from '@yumpoo/api-client'
import { describe, expect, it } from 'vitest'
import type { SheetCell } from '../../import/spreadsheet'
import {
  assignColumn,
  assignField,
  autoMapColumns,
  buildImportCatalog,
  buildImportRequest,
  buildImportTable,
  canLeaveMapping,
  collectValues,
  countUnrecognizedDueDates,
  describeIssue,
  describeResult,
  emptyChoices,
  emptyMapping,
  fieldOfColumn,
  importableCount,
  importFieldLabel,
  importLimitIssues,
  IMPORT_MAX_ROWS,
  mappedColumnCount,
  resolveAssignee,
  resolveContent,
  resolvePriority,
  resolveStatus,
  setChoice,
  splitNames,
  unresolvedAssigneeNames,
  type ImportCatalogSource,
  type ImportSettings,
} from './workItemImportModel'

const HEADER: SheetCell[] = ['ID', '标题', '优先级', '状态', '处理人', '预计结束', '父需求', '完成工时', '需求类别', null]

/** 按原平台导出的样子构造：子项标题缩进 8 个空格、英文优先级、分号结尾的处理人、父需求填父项标题。 */
const SHEET: SheetCell[][] = [
  HEADER,
  ['1', 'BCS 项目周会', 'High', '已完成', '李嘉豪;', null, null, 2, '任务', null],
  ['2', '开发进度跟进', 'Medium', '进行中', '马郅昱;李刚;', '2026-10-31', null, 0.5, '任务', null],
  ['3', '开发进度跟进', 'Medium', '已完成', '李刚;', '2026-10-31', null, 1, '任务', null],
  ['4', '服务器升级', 'Urgent', '规划中', '马郅昱;', '2026/11/2', null, 5.5, '需求', null],
  ['5', '        升级前备份', 'Low', '已实现', '马郅昱;', '下周五', '服务器升级', 2, '任务', null],
  [null, null, null, null, null, null, null, null, null, null],
  ['6', '        升级后验证', null, '开发中', '', null, '服务器升级', 3.5, '需求', null],
]

const SOURCE: ImportCatalogSource = {
  contents: [
    { id: 'c-req', code: 'REQUIREMENT', name: '需求', active: true },
    { id: 'c-task', code: 'TASK', name: '任务', active: true },
    { id: 'c-bug', code: 'DEFECT', name: '缺陷', active: false },
  ],
  statuses: [
    { code: 'DONE', displayName: '已完成', active: true, sortOrder: 40 },
    { code: 'NOT_STARTED', displayName: '未开始', active: true, sortOrder: 10 },
    { code: 'IN_PROGRESS', displayName: '进行中', active: true, sortOrder: 20 },
    { code: 'CANCELED', displayName: '已取消', active: false, sortOrder: 50 },
  ],
  priorities: [
    { code: 'LOW', displayName: 'Low', active: true, sortOrder: 10 },
    { code: 'MEDIUM', displayName: 'Medium', active: true, sortOrder: 20 },
    { code: 'HIGH', displayName: 'High', active: true, sortOrder: 30 },
    { code: 'URGENT', displayName: 'Urgent', active: true, sortOrder: 40 },
  ],
  members: [
    { userId: 'u-li-jh', displayName: '李嘉豪', membershipStatus: 'ACTIVE', employmentStatus: 'ACTIVE', accountStatus: 'ENABLED' },
    { userId: 'u-ma', displayName: '马郅昱', membershipStatus: 'ACTIVE', employmentStatus: 'ACTIVE', accountStatus: 'ENABLED' },
    { userId: 'u-li-g1', displayName: '李刚', membershipStatus: 'ACTIVE', employmentStatus: 'ACTIVE', accountStatus: 'ENABLED' },
    { userId: 'u-li-g2', displayName: '李刚', membershipStatus: 'ACTIVE', employmentStatus: 'ACTIVE', accountStatus: 'ENABLED' },
    { userId: 'u-gone', displayName: '已移出', membershipStatus: 'REMOVED', employmentStatus: 'ACTIVE', accountStatus: 'ENABLED' },
    { userId: 'u-left', displayName: '已离职', membershipStatus: 'ACTIVE', employmentStatus: 'INACTIVE', accountStatus: 'ENABLED' },
  ],
}

function load() {
  const built = buildImportTable(SHEET)
  if (!built.ok) throw new Error(built.message)
  const catalog = buildImportCatalog(SOURCE)
  const settings: ImportSettings = { mapping: autoMapColumns(built.table.headers), defaultContentId: 'c-task', choices: emptyChoices() }
  return { table: built.table, catalog, settings }
}

describe('buildImportTable', () => {
  it('第一行非空行作表头，全空行忽略，行号保持原位置', () => {
    const { table } = load()
    expect(table.headers).toEqual(['ID', '标题', '优先级', '状态', '处理人', '预计结束', '父需求', '完成工时', '需求类别', '第 10 列'])
    expect(table.rows.map(row => row.rowNumber)).toEqual([2, 3, 4, 5, 6, 8])
  })

  it('表头前的空行被跳过', () => {
    const built = buildImportTable([[null, null], ['标题', '状态'], ['甲', '未开始']])
    expect(built).toMatchObject({ ok: true, table: { headers: ['标题', '状态'], rows: [{ rowNumber: 3 }] } })
  })

  it('空文件、只有表头和超过上限都给出原因', () => {
    expect(buildImportTable([])).toMatchObject({ ok: false, message: '文件里没有内容。' })
    expect(buildImportTable([['标题']])).toMatchObject({ ok: false })
    const many: SheetCell[][] = [['标题'], ...Array.from({ length: IMPORT_MAX_ROWS + 1 }, (_, index) => [`项 ${index}`])]
    const tooMany = buildImportTable(many)
    expect(tooMany).toMatchObject({ ok: false })
    expect(tooMany.ok ? '' : tooMany.message).toContain('2000')
    expect(buildImportTable(many.slice(0, IMPORT_MAX_ROWS + 1)).ok).toBe(true)
  })
})

describe('autoMapColumns', () => {
  it('按同义词映射样例文件，ID、完成工时和无名列保持不导入', () => {
    const { table, settings } = load()
    expect(settings.mapping).toEqual({
      title: 1, priority: 2, status: 3, assignees: 4, dueDate: 5, parent: 6, content: 8, notes: null,
    })
    expect(mappedColumnCount(settings.mapping)).toBe(7)
    expect(table.headers.length - mappedColumnCount(settings.mapping)).toBe(3)
    expect(fieldOfColumn(settings.mapping, 7)).toBeNull()
  })

  it('忽略大小写和两端空白，同义词表头不会被两个字段重复占用', () => {
    const mapping = autoMapColumns([' Title ', 'STATUS', 'Owner', '名称', 'Notes'])
    expect(mapping).toMatchObject({ title: 0, status: 1, assignees: 2, notes: 4 })
    expect(mapping.content).toBeNull()
  })
})

describe('assignField / assignColumn', () => {
  it('每个字段只用一列，每列只对应一个字段', () => {
    const { settings } = load()
    const moved = assignField(settings.mapping, 'status', 2)
    expect(moved.status).toBe(2)
    expect(moved.priority).toBeNull()

    const reassigned = assignColumn(settings.mapping, 7, 'dueDate')
    expect(reassigned.dueDate).toBe(7)
    expect(fieldOfColumn(reassigned, 5)).toBeNull()

    const dropped = assignColumn(settings.mapping, 1, null)
    expect(dropped.title).toBeNull()
  })
})

describe('collectValues', () => {
  it('类别和状态把空白单列为「(空白)」，优先级不列空白', () => {
    const { table, settings } = load()
    expect(collectValues(table, settings.mapping.status!, 'status')).toEqual([
      { key: '已完成', label: '已完成', count: 2 },
      { key: '进行中', label: '进行中', count: 1 },
      { key: '规划中', label: '规划中', count: 1 },
      { key: '已实现', label: '已实现', count: 1 },
      { key: '开发中', label: '开发中', count: 1 },
    ])
    expect(collectValues(table, settings.mapping.priority!, 'priority').map(value => value.key))
      .toEqual(['High', 'Medium', 'Urgent', 'Low'])
    const blank = buildImportTable([['标题', '状态'], ['甲', ''], ['乙', '未开始'], ['丙', '  ']])
    if (!blank.ok) throw new Error('unexpected')
    expect(collectValues(blank.table, 1, 'status')).toEqual([
      { key: '', label: '(空白)', count: 2 },
      { key: '未开始', label: '未开始', count: 1 },
    ])
  })

  it('处理人按分隔符拆分后统计，每行同名只算一次', () => {
    const { table, settings } = load()
    expect(collectValues(table, settings.mapping.assignees!, 'assignees')).toEqual([
      { key: '李嘉豪', label: '李嘉豪', count: 1 },
      { key: '马郅昱', label: '马郅昱', count: 3 },
      { key: '李刚', label: '李刚', count: 2 },
    ])
    expect(splitNames('甲;乙；丙, 丁，戊、己　庚 ;')).toEqual(['甲', '乙', '丙', '丁', '戊', '己', '庚'])
  })
})

describe('取值解析', () => {
  it('类别、优先级按名称或编码忽略大小写自动匹配', () => {
    const { catalog, settings } = load()
    expect(resolveContent(catalog, settings, '需求')).toBe('c-req')
    expect(resolveContent(catalog, settings, ' task ')).toBe('c-task')
    expect(resolvePriority(catalog, settings, 'High')).toBe('HIGH')
    expect(resolvePriority(catalog, settings, 'urgent')).toBe('URGENT')
  })

  it('停用的类别不参与匹配，匹配不到的类别用「导入为类别」', () => {
    const { catalog, settings } = load()
    expect(resolveContent(catalog, settings, '缺陷')).toBe('c-task')
    expect(resolveContent(catalog, { ...settings, defaultContentId: 'c-req' }, '未知')).toBe('c-req')
    expect(resolveContent(catalog, settings, '')).toBe('c-task')
  })

  it('规划中、已实现、开发中匹配不到，状态默认未开始，已完成、进行中能匹配', () => {
    const { catalog, settings } = load()
    for (const name of ['规划中', '已实现', '开发中', '']) expect(resolveStatus(catalog, settings, name)).toBe('NOT_STARTED')
    expect(resolveStatus(catalog, settings, '已完成')).toBe('DONE')
    expect(resolveStatus(catalog, settings, 'in_progress')).toBe('IN_PROGRESS')
    expect(catalog.statuses.map(option => option.value)).toEqual(['NOT_STARTED', 'IN_PROGRESS', 'DONE'])
  })

  it('匹配不到的优先级留空，手动选择优先于自动匹配', () => {
    const { catalog, settings } = load()
    expect(resolvePriority(catalog, settings, '紧急')).toBeNull()
    expect(resolvePriority(catalog, { ...settings, choices: setChoice(settings.choices, 'priority', '紧急', 'URGENT') }, '紧急')).toBe('URGENT')
    expect(resolvePriority(catalog, { ...settings, choices: setChoice(settings.choices, 'priority', 'High', null) }, 'High')).toBeNull()
    expect(resolveStatus(catalog, { ...settings, choices: setChoice(settings.choices, 'status', '规划中', 'IN_PROGRESS') }, '规划中')).toBe('IN_PROGRESS')
  })

  it('处理人只在有效成员里匹配：已移出、已离职的当作匹配不到', () => {
    const { catalog, settings } = load()
    expect(resolveAssignee(catalog, settings, '马郅昱')).toEqual({ kind: 'resolved', userId: 'u-ma' })
    expect(resolveAssignee(catalog, settings, '已移出')).toEqual({ kind: 'resolved', userId: null })
    expect(resolveAssignee(catalog, settings, '已离职')).toEqual({ kind: 'resolved', userId: null })
    expect(resolveAssignee(catalog, settings, '陌生人')).toEqual({ kind: 'resolved', userId: null })
  })

  it('同名的多位成员必须手动选择，选定前「下一步」不可用', () => {
    const { table, catalog, settings } = load()
    expect(resolveAssignee(catalog, settings, '李刚')).toMatchObject({ kind: 'ambiguous' })
    expect(unresolvedAssigneeNames(table, catalog, settings)).toEqual(['李刚'])
    expect(canLeaveMapping(table, catalog, settings)).toBe(false)

    const chosen = { ...settings, choices: setChoice(settings.choices, 'assignees', '李刚', 'u-li-g2') }
    expect(resolveAssignee(catalog, chosen, '李刚')).toEqual({ kind: 'resolved', userId: 'u-li-g2' })
    expect(canLeaveMapping(table, catalog, chosen)).toBe(true)
    expect(canLeaveMapping(table, catalog, { ...settings, choices: setChoice(settings.choices, 'assignees', '李刚', null) })).toBe(true)
  })

  it('处理人列未映射时不要求选择；标题未映射时「下一步」不可用', () => {
    const { table, catalog, settings } = load()
    expect(canLeaveMapping(table, catalog, { ...settings, mapping: assignColumn(settings.mapping, 4, null) })).toBe(true)
    expect(canLeaveMapping(table, catalog, { ...settings, mapping: assignColumn(settings.mapping, 1, null) })).toBe(false)
  })
})

describe('countUnrecognizedDueDates', () => {
  it('有内容却无法识别的截止日期按行计数，空白不算', () => {
    const { table, settings } = load()
    expect(countUnrecognizedDueDates(table, settings.mapping)).toBe(1)
    expect(countUnrecognizedDueDates(table, assignColumn(settings.mapping, 5, null))).toBe(0)
  })
})

describe('buildImportRequest', () => {
  const resolved = (): ReturnType<typeof load> => {
    const loaded = load()
    return { ...loaded, settings: { ...loaded.settings, choices: setChoice(loaded.settings.choices, 'assignees', '李刚', 'u-li-g1') } }
  }

  it('字段路径使用请求下标还原文件行号和字段名', () => {
    const { table, catalog, settings } = resolved()
    const request = buildImportRequest(table, catalog, settings, WorkItemImportDuplicateMode.Update, false)
    expect(importFieldLabel(request, 'rows[1].notes')).toBe('第 3 行 · 备注')
  })

  it('标题去缩进，字段值按映射转换，父需求原样带父项标题', () => {
    const { table, catalog, settings } = resolved()
    const request = buildImportRequest(table, catalog, settings, WorkItemImportDuplicateMode.Skip, true)
    expect(request.dryRun).toBe(true)
    expect(request.duplicateMode).toBe('SKIP')
    expect(request.fields).toEqual([
      WorkItemImportField.Content, WorkItemImportField.Status, WorkItemImportField.Priority,
      WorkItemImportField.Assignees, WorkItemImportField.DueDate, WorkItemImportField.Parent,
    ])
    expect(request.rows).toHaveLength(6)
    expect(request.rows[0]).toEqual({
      rowNumber: 2, title: 'BCS 项目周会', contentId: 'c-task', statusCode: 'DONE', priority: 'HIGH',
      assigneeUserIds: ['u-li-jh'], dueDate: null, parentTitle: null, notes: null,
    })
    expect(request.rows[1]).toMatchObject({ assigneeUserIds: ['u-ma', 'u-li-g1'], dueDate: new Date('2026-10-31T00:00:00.000Z') })
    expect(request.rows[3]).toMatchObject({ rowNumber: 5, title: '服务器升级', contentId: 'c-req', statusCode: 'NOT_STARTED', priority: 'URGENT' })
    expect(request.rows[4]).toMatchObject({
      rowNumber: 6, title: '升级前备份', statusCode: 'NOT_STARTED', priority: 'LOW', dueDate: null, parentTitle: '服务器升级',
    })
    expect(request.rows[5]).toMatchObject({
      rowNumber: 8, title: '升级后验证', contentId: 'c-req', priority: null, assigneeUserIds: [], parentTitle: '服务器升级',
    })
  })

  it('同名的重复标题各占一行，由服务端按模式处理', () => {
    const { table, catalog, settings } = resolved()
    const titles = buildImportRequest(table, catalog, settings, WorkItemImportDuplicateMode.Create, false).rows.map(row => row.title)
    expect(titles.filter(title => title === '开发进度跟进')).toHaveLength(2)
  })

  it('未映射的字段取默认值且不出现在 fields 里', () => {
    const { table, catalog, settings } = load()
    const only = { ...settings, mapping: { ...emptyMapping(), title: 1 } }
    const request = buildImportRequest(table, catalog, only, WorkItemImportDuplicateMode.Update, false)
    expect(request.fields).toEqual([])
    expect(request.rows[0]).toEqual({
      rowNumber: 2, title: 'BCS 项目周会', contentId: 'c-task', statusCode: 'NOT_STARTED', priority: null,
      assigneeUserIds: [], dueDate: null, parentTitle: null, notes: null,
    })
  })

  it('无法识别的截止日期留空，备注按列带入', () => {
    const built = buildImportTable([['标题', '截止日期', '备注'], ['甲', '下周五', ' 说明 '], ['乙', 46_000, '']])
    if (!built.ok) throw new Error('unexpected')
    const catalog = buildImportCatalog(SOURCE)
    const settings: ImportSettings = { mapping: autoMapColumns(built.table.headers), defaultContentId: 'c-task', choices: emptyChoices() }
    const rows = buildImportRequest(built.table, catalog, settings, WorkItemImportDuplicateMode.Create, false).rows
    expect(rows[0]).toMatchObject({ dueDate: null, notes: '说明' })
    expect(rows[1]).toMatchObject({ dueDate: new Date('2025-12-09T00:00:00.000Z'), notes: null })
  })

  it('超长备注和过多处理人必须先修正，不静默截断', () => {
    const names = Array.from({ length: 21 }, (_, index) => `成员${index}`)
    const notes = '字'.repeat(16385)
    const built = buildImportTable([['标题', '备注', '处理人'], ['甲', notes, names.join(';')]])
    if (!built.ok) throw new Error('unexpected')
    const catalog = { ...buildImportCatalog(SOURCE), members: names.map((name, index) => ({ value: `u-${index}`, label: name, match: [name] })) }
    const settings: ImportSettings = { mapping: autoMapColumns(built.table.headers), defaultContentId: 'c-task', choices: emptyChoices() }
    const request = buildImportRequest(built.table, catalog, settings, WorkItemImportDuplicateMode.Create, false)
    expect(request.rows[0]!.notes).toBe(notes)
    expect(request.rows[0]!.assigneeUserIds).toHaveLength(21)
    expect(importLimitIssues(built.table, catalog, settings)).toEqual(['第 2 行备注超过 16384 个字符', '第 2 行处理人超过 20 位'])
    expect(canLeaveMapping(built.table, catalog, settings)).toBe(false)
    expect(canLeaveMapping(built.table, catalog, { ...settings, mapping: { ...emptyMapping(), title: 0 } })).toBe(true)
  })
})

describe('结果说明', () => {
  it('把问题编码翻译成中文，未知编码保留原编码', () => {
    expect(describeIssue({ code: 'DUPLICATE_EXISTS', detail: null })).toBe('项目中已有同名工作项，已跳过')
    expect(describeIssue({ code: 'PARENT_NOT_FOUND', detail: null })).toContain('顶层')
    expect(describeIssue({ code: 'SOMETHING_NEW', detail: null })).toBe('未知问题（SOMETHING_NEW）')
    expect(describeIssue({ code: 'SOMETHING_NEW', detail: '补充' })).toBe('SOMETHING_NEW：补充')
  })

  it('结果提示与可导入数量', () => {
    const counts = { createdCount: 3, updatedCount: 2, skippedCount: 1, failedCount: 4 }
    expect(describeResult(counts)).toBe('导入完成：新增 3，更新 2，跳过 1，失败 4')
    expect(importableCount(counts)).toBe(5)
  })
})
