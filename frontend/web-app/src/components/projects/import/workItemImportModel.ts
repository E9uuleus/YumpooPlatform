/** 工作项导入的纯模型：表头识别、列映射、取值映射与请求组装；不依赖 Vue 与网络。 */
import {
  WorkItemImportDuplicateMode,
  WorkItemImportField,
  WorkItemImportOutcome,
  type WorkItemImportIssue,
  type WorkItemImportRequest,
  type WorkItemImportResult,
  type WorkItemImportRow,
} from '@yumpoo/api-client'
import { cellDate, cellText, type SheetCell } from '../../import/spreadsheet'

export const IMPORT_MAX_ROWS = 2000

export type ImportField = 'title' | 'content' | 'status' | 'priority' | 'assignees' | 'dueDate' | 'parent' | 'notes'
export type OptionalImportField = Exclude<ImportField, 'title'>

/** 列下拉里可选的目标字段，顺序即下拉顺序。 */
export const OPTIONAL_IMPORT_FIELDS: readonly OptionalImportField[] = ['content', 'status', 'priority', 'assignees', 'dueDate', 'parent', 'notes']

export const IMPORT_FIELD_LABELS: Record<ImportField, string> = {
  title: '标题',
  content: '类别',
  status: '状态',
  priority: '优先级',
  assignees: '处理人',
  dueDate: '截止日期',
  parent: '父工作项',
  notes: '备注',
}

const FIELD_SYNONYMS: Record<ImportField, readonly string[]> = {
  title: ['标题', '名称', '工作项', 'title', 'name'],
  content: ['类别', '需求类别', '类型', '分类', 'type'],
  status: ['状态', 'status'],
  priority: ['优先级', 'priority'],
  assignees: ['处理人', '负责人', '执行人', '指派给', 'assignee', 'owner'],
  dueDate: ['截止日期', '预计结束', '结束日期', '截止时间', '到期日', 'due'],
  parent: ['父需求', '父工作项', '父任务', '父级', 'parent'],
  notes: ['备注', 'notes'],
}

const FIELD_ORDER: readonly ImportField[] = ['title', ...OPTIONAL_IMPORT_FIELDS]

const API_FIELDS: Record<OptionalImportField, WorkItemImportField> = {
  content: WorkItemImportField.Content,
  status: WorkItemImportField.Status,
  priority: WorkItemImportField.Priority,
  assignees: WorkItemImportField.Assignees,
  dueDate: WorkItemImportField.DueDate,
  parent: WorkItemImportField.Parent,
  notes: WorkItemImportField.Notes,
}

export const TITLE_MAX = 1000
export const NOTES_MAX = 16384
export const ASSIGNEES_MAX = 20

/** 取值映射里「空白」一项的键。 */
export const BLANK_VALUE = ''
export const BLANK_LABEL = '(空白)'

// ---- 表格 ----

export interface ImportTableRow {
  /** 表格中的行号，从 1 开始，与表格软件里看到的一致。 */
  rowNumber: number
  cells: SheetCell[]
}

export interface ImportTable {
  headers: string[]
  rows: ImportTableRow[]
}

export type ImportTableResult = { ok: true; table: ImportTable } | { ok: false; message: string }

const isBlank = (cell: SheetCell | undefined): boolean => cellText(cell) === ''

/** 第一行非空行是表头，其后的全空行忽略；行号保留原表格中的位置。 */
export function buildImportTable(cells: readonly (readonly SheetCell[])[]): ImportTableResult {
  const headerIndex = cells.findIndex(row => row.some(cell => !isBlank(cell)))
  if (headerIndex < 0) return { ok: false, message: '文件里没有内容。' }
  const width = cells.slice(headerIndex).reduce((most, row) => Math.max(most, row.length), 0)
  const header = cells[headerIndex]!
  const headers = Array.from({ length: width }, (_, column) => cellText(header[column]) || `第 ${column + 1} 列`)
  const rows: ImportTableRow[] = []
  cells.forEach((row, index) => {
    if (index <= headerIndex || row.every(cell => isBlank(cell))) return
    rows.push({ rowNumber: index + 1, cells: Array.from({ length: width }, (_, column) => row[column] ?? null) })
  })
  if (!rows.length) return { ok: false, message: '文件里没有数据行，第一行非空行会作为表头。' }
  if (rows.length > IMPORT_MAX_ROWS) {
    return { ok: false, message: `文件有 ${rows.length} 行数据，超过 ${IMPORT_MAX_ROWS} 行的上限，请拆分后分次导入。` }
  }
  return { ok: true, table: { headers, rows } }
}

// ---- 列映射 ----

/** 目标字段 → 文件中的列下标；null 表示未映射。每列最多对应一个字段。 */
export type ColumnMapping = Record<ImportField, number | null>

export const emptyMapping = (): ColumnMapping => ({
  title: null, content: null, status: null, priority: null, assignees: null, dueDate: null, parent: null, notes: null,
})

/** 表头按同义词（忽略大小写和两端空白）自动映射；一列只会被一个字段占用，其余列保持不导入。 */
export function autoMapColumns(headers: readonly string[]): ColumnMapping {
  const mapping = emptyMapping()
  const taken = new Set<number>()
  const normalized = headers.map(header => header.trim().toLowerCase())
  for (const field of FIELD_ORDER) {
    const column = normalized.findIndex((header, index) => !taken.has(index) && FIELD_SYNONYMS[field].includes(header))
    if (column < 0) continue
    mapping[field] = column
    taken.add(column)
  }
  return mapping
}

export function fieldOfColumn(mapping: ColumnMapping, column: number): ImportField | null {
  return FIELD_ORDER.find(field => mapping[field] === column) ?? null
}

/** 让某个字段改用指定列（null 为取消映射）；这一列原先对应的字段随之取消。 */
export function assignField(mapping: ColumnMapping, field: ImportField, column: number | null): ColumnMapping {
  const next = { ...mapping }
  if (column !== null) for (const other of FIELD_ORDER) if (next[other] === column) next[other] = null
  next[field] = column
  return next
}

/** 设置某一列导入为哪个字段（null 为不导入）；该字段原先使用的列随之取消。 */
export function assignColumn(mapping: ColumnMapping, column: number, field: ImportField | null): ColumnMapping {
  const cleared = { ...mapping }
  for (const other of FIELD_ORDER) if (cleared[other] === column) cleared[other] = null
  return field ? assignField(cleared, field, column) : cleared
}

export function mappedColumnCount(mapping: ColumnMapping): number {
  return FIELD_ORDER.filter(field => mapping[field] !== null).length
}

export function mappedOptionalFields(mapping: ColumnMapping): OptionalImportField[] {
  return OPTIONAL_IMPORT_FIELDS.filter(field => mapping[field] !== null)
}

// ---- 取值映射 ----

/** 可选的目标值；`match` 是能自动匹配到它的名称与编码。 */
export interface ImportOption {
  value: string
  label: string
  match: string[]
}

export interface ImportCatalog {
  contents: ImportOption[]
  statuses: ImportOption[]
  priorities: ImportOption[]
  members: ImportOption[]
  /** 状态取值未匹配时使用的状态编码。 */
  defaultStatusCode: string
}

export interface ImportCatalogSource {
  contents: readonly { id: string; code: string; name: string; active: boolean }[]
  statuses: readonly { code: string; displayName: string; active: boolean; sortOrder: number }[]
  priorities: readonly { code: string; displayName: string; active: boolean; sortOrder: number }[]
  members: readonly { userId: string; displayName: string; membershipStatus: string; employmentStatus: string; accountStatus: string }[]
}

const NOT_STARTED = 'NOT_STARTED'

/** 只收启用的类别、状态、优先级，以及项目里在职、账号启用的有效成员。 */
export function buildImportCatalog(source: ImportCatalogSource): ImportCatalog {
  const bySort = <T extends { sortOrder: number }>(items: readonly T[]): T[] => [...items].sort((left, right) => left.sortOrder - right.sortOrder)
  const statuses = bySort(source.statuses.filter(item => item.active))
    .map(item => ({ value: item.code, label: item.displayName, match: [item.displayName, item.code] }))
  return {
    contents: source.contents.filter(item => item.active)
      .map(item => ({ value: item.id, label: item.name, match: [item.name, item.code] })),
    statuses,
    priorities: bySort(source.priorities.filter(item => item.active))
      .map(item => ({ value: item.code, label: item.displayName, match: [item.displayName, item.code] })),
    members: source.members
      .filter(item => item.membershipStatus === 'ACTIVE' && item.employmentStatus === 'ACTIVE' && item.accountStatus === 'ENABLED')
      .map(item => ({ value: item.userId, label: item.displayName, match: [item.displayName] })),
    defaultStatusCode: statuses.some(item => item.value === NOT_STARTED) ? NOT_STARTED : statuses[0]?.value ?? NOT_STARTED,
  }
}

export type ValueKind = 'content' | 'status' | 'priority' | 'assignees'

export const VALUE_KINDS: readonly ValueKind[] = ['content', 'status', 'priority', 'assignees']

export interface ImportValue {
  /** 去掉两端空白后的原文；空白值为 BLANK_VALUE。 */
  key: string
  label: string
  /** 出现该值的行数。 */
  count: number
}

/** 处理人单元格按 `;；,，、` 和空白拆成姓名。 */
export function splitNames(text: string): string[] {
  return text.split(/[;；,，、\s]+/u).filter(Boolean)
}

/** 列出一列里每个不同的值及其出现的行数，按首次出现的顺序；处理人列按拆分后的姓名统计。 */
export function collectValues(table: ImportTable, column: number, kind: ValueKind): ImportValue[] {
  const found = new Map<string, number>()
  for (const row of table.rows) {
    const text = cellText(row.cells[column])
    const keys = kind === 'assignees' ? splitNames(text) : [text]
    for (const key of new Set(keys)) found.set(key, (found.get(key) ?? 0) + 1)
  }
  return [...found].filter(([key]) => key !== BLANK_VALUE || kind === 'content' || kind === 'status')
    .map(([key, count]) => ({ key, label: key === BLANK_VALUE ? BLANK_LABEL : key, count }))
}

/** 用户手动选定的取值；没有手动选定的值按自动匹配和默认值解析。null 表示留空或不分配。 */
export interface ValueChoices {
  content: ReadonlyMap<string, string>
  status: ReadonlyMap<string, string>
  priority: ReadonlyMap<string, string | null>
  assignees: ReadonlyMap<string, string | null>
}

export const emptyChoices = (): ValueChoices => ({
  content: new Map(), status: new Map(), priority: new Map(), assignees: new Map(),
})

export function setChoice<K extends ValueKind>(choices: ValueChoices, kind: K, key: string, value: string | null): ValueChoices {
  return { ...choices, [kind]: new Map(choices[kind] as ReadonlyMap<string, string | null>).set(key, value) }
}

function matching(options: readonly ImportOption[], text: string): ImportOption[] {
  const needle = text.trim().toLowerCase()
  if (!needle) return []
  return options.filter(option => option.match.some(name => name.trim().toLowerCase() === needle))
}

/** 名称或编码忽略大小写完全相同即匹配，匹配到多个时取第一个。 */
export function autoMatch(options: readonly ImportOption[], text: string): string | undefined {
  return matching(options, text)[0]?.value
}

export interface ImportSettings {
  mapping: ColumnMapping
  /** 「导入为类别」：类别未映射或取值匹配不到时使用。 */
  defaultContentId: string
  choices: ValueChoices
}

export const resolveContent = (catalog: ImportCatalog, settings: ImportSettings, key: string): string =>
  settings.choices.content.get(key) ?? autoMatch(catalog.contents, key) ?? settings.defaultContentId

export const resolveStatus = (catalog: ImportCatalog, settings: ImportSettings, key: string): string =>
  settings.choices.status.get(key) ?? autoMatch(catalog.statuses, key) ?? catalog.defaultStatusCode

export function resolvePriority(catalog: ImportCatalog, settings: ImportSettings, key: string): string | null {
  const chosen = settings.choices.priority.get(key)
  return chosen !== undefined ? chosen : autoMatch(catalog.priorities, key) ?? null
}

export type AssigneeResolution =
  | { kind: 'resolved'; userId: string | null }
  | { kind: 'ambiguous'; candidates: ImportOption[] }

/** 同名的多位成员必须手动选一个；匹配不到的默认不分配。 */
export function resolveAssignee(catalog: ImportCatalog, settings: ImportSettings, name: string): AssigneeResolution {
  const chosen = settings.choices.assignees.get(name)
  if (chosen !== undefined) return { kind: 'resolved', userId: chosen }
  const candidates = matching(catalog.members, name)
  if (candidates.length > 1) return { kind: 'ambiguous', candidates }
  return { kind: 'resolved', userId: candidates[0]?.value ?? null }
}

/** 还没有选定的同名处理人。 */
export function unresolvedAssigneeNames(table: ImportTable, catalog: ImportCatalog, settings: ImportSettings): string[] {
  const column = settings.mapping.assignees
  if (column === null) return []
  return collectValues(table, column, 'assignees')
    .filter(value => resolveAssignee(catalog, settings, value.key).kind === 'ambiguous')
    .map(value => value.key)
}

/** 「下一步」可用：标题已映射，且同名的处理人都已选定。 */
export function canLeaveMapping(table: ImportTable, catalog: ImportCatalog, settings: ImportSettings): boolean {
  return settings.mapping.title !== null && unresolvedAssigneeNames(table, catalog, settings).length === 0
}

/** 截止日期列里有内容却无法识别为日期的行数。 */
export function countUnrecognizedDueDates(table: ImportTable, mapping: ColumnMapping): number {
  const column = mapping.dueDate
  if (column === null) return 0
  return table.rows.filter(row => !isBlank(row.cells[column]) && cellDate(row.cells[column]) === null).length
}

// ---- 请求 ----

const optionalText = (cell: SheetCell | undefined, max: number): string | null => cellText(cell).slice(0, max) || null

export function buildImportRows(table: ImportTable, catalog: ImportCatalog, settings: ImportSettings): WorkItemImportRow[] {
  const { mapping } = settings
  const cellOf = (row: ImportTableRow, field: ImportField): SheetCell | undefined =>
    mapping[field] === null ? undefined : row.cells[mapping[field]]
  return table.rows.map(row => {
    const assigneeIds = new Set<string>()
    if (mapping.assignees !== null) {
      for (const name of splitNames(cellText(cellOf(row, 'assignees')))) {
        const resolved = resolveAssignee(catalog, settings, name)
        if (resolved.kind === 'resolved' && resolved.userId) assigneeIds.add(resolved.userId)
      }
    }
    const due = cellDate(cellOf(row, 'dueDate'))
    return {
      rowNumber: row.rowNumber,
      title: cellText(cellOf(row, 'title')).slice(0, TITLE_MAX),
      contentId: mapping.content === null ? settings.defaultContentId : resolveContent(catalog, settings, cellText(cellOf(row, 'content'))),
      statusCode: mapping.status === null ? catalog.defaultStatusCode : resolveStatus(catalog, settings, cellText(cellOf(row, 'status'))),
      priority: mapping.priority === null ? null : resolvePriority(catalog, settings, cellText(cellOf(row, 'priority'))),
      assigneeUserIds: [...assigneeIds].slice(0, ASSIGNEES_MAX),
      dueDate: due ? new Date(`${due}T00:00:00.000Z`) : null,
      parentTitle: optionalText(cellOf(row, 'parent'), TITLE_MAX),
      notes: optionalText(cellOf(row, 'notes'), NOTES_MAX),
    }
  })
}

export function buildImportRequest(
  table: ImportTable,
  catalog: ImportCatalog,
  settings: ImportSettings,
  duplicateMode: WorkItemImportDuplicateMode,
  dryRun: boolean,
): WorkItemImportRequest {
  return {
    dryRun,
    duplicateMode,
    fields: mappedOptionalFields(settings.mapping).map(field => API_FIELDS[field]),
    rows: buildImportRows(table, catalog, settings),
  }
}

// ---- 结果 ----

const ISSUE_TEXT: Record<string, string> = {
  TITLE_INVALID: '标题为空或超过 300 个字符',
  DUPLICATE_EXISTS: '项目中已有同名工作项，已跳过',
  MATCH_AMBIGUOUS: '项目中有多个同名工作项，无法确定更新哪一个',
  DUPLICATE_IN_FILE: '文件里该标题出现多行，且项目中已有同名工作项，无法确定对应关系',
  CONTENT_INVALID: '类别不可用',
  STATUS_INVALID: '状态不可用',
  PRIORITY_INVALID: '优先级不可用',
  ASSIGNEE_INVALID: '处理人不是本项目的有效成员',
  PARENT_NOT_FOUND: '找不到父工作项，已作为顶层工作项导入',
  PARENT_AMBIGUOUS: '有多个同名的父工作项，已作为顶层工作项导入',
  PARENT_FLATTENED: '父工作项本身是子项，已挂到它的最上层父项下',
  PARENT_CYCLE: '父子关系指向自己或成环，已作为顶层工作项导入',
  PARENT_HAS_CHILDREN: '该工作项已有子项，不能再变成子项，保持原样',
}

export function describeIssue(issue: Pick<WorkItemImportIssue, 'code' | 'detail'>): string {
  const known = ISSUE_TEXT[issue.code]
  if (known) return known
  return issue.detail ? `${issue.code}：${issue.detail}` : `未知问题（${issue.code}）`
}

export interface ImportIssueRow {
  rowNumber: number
  title: string
  outcome: WorkItemImportOutcome
  messages: string[]
}

/** 带问题或无法导入的行，标题取自文件，按行号排序。 */
export function issueRows(result: Pick<WorkItemImportResult, 'rows'>, table: ImportTable, mapping: ColumnMapping): ImportIssueRow[] {
  const titles = new Map(table.rows.map(row => [row.rowNumber, mapping.title === null ? '' : cellText(row.cells[mapping.title])]))
  return result.rows
    .filter(row => row.issues.length > 0 || row.outcome === WorkItemImportOutcome.Error)
    .map(row => ({
      rowNumber: row.rowNumber,
      title: titles.get(row.rowNumber) || '(无标题)',
      outcome: row.outcome,
      messages: row.issues.map(describeIssue),
    }))
    .sort((left, right) => left.rowNumber - right.rowNumber)
}

export function describeResult(result: Pick<WorkItemImportResult, 'createdCount' | 'updatedCount' | 'skippedCount' | 'failedCount'>): string {
  return `导入完成：新增 ${result.createdCount}，更新 ${result.updatedCount}，跳过 ${result.skippedCount}，失败 ${result.failedCount}`
}

/** 「导入 N 项」里的 N：会新增或更新的行数。 */
export const importableCount = (result: Pick<WorkItemImportResult, 'createdCount' | 'updatedCount'>): number =>
  result.createdCount + result.updatedCount

export const DUPLICATE_MODE_OPTIONS = [
  { value: WorkItemImportDuplicateMode.Create, title: '全部新增', description: '即使项目中已有同名工作项，也全部导入' },
  { value: WorkItemImportDuplicateMode.Skip, title: '跳过已有', description: '标题与项目中已有工作项相同的行不导入' },
  { value: WorkItemImportDuplicateMode.Update, title: '更新已有', description: '覆盖同名工作项的已映射字段，空单元格会清空对应字段；没有同名项的行新增' },
] as const
