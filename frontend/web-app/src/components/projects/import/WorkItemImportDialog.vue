<script setup lang="ts">
import YpNotice from '../../yp/YpNotice.vue'
import {
  WorkItemImportDuplicateMode,
  WorkItemImportOutcome,
  readCsrfToken,
  type Content,
  type ProjectMember,
  type WorkItemImportResult,
  type WorkItemPriorityLabel,
  type WorkItemStatusLabel,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElCheckbox,
  ElIcon,
  ElDialog,
  ElMessage,
  ElOption as ElOptionRaw,
  ElSelect as ElSelectRaw,
} from 'element-plus'
import { computed, ref, shallowRef, watch, type Component, type DefineComponent } from 'vue'
import { workItemsApi } from '../../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../../api/problems'
import InlineProblem from '../../InlineProblem.vue'
import ImportFileDrop from '../../import/ImportFileDrop.vue'
import ImportSteps from '../../import/ImportSteps.vue'
import { cellText, readSpreadsheet, SpreadsheetError } from '../../import/spreadsheet'
import { CircleCloseFilled, CirclePlus, RefreshRight, Remove, Right, WarningFilled } from '@element-plus/icons-vue'
import YpAssigneeStack from '../../yp/YpAssigneeStack.vue'
import { workItemLabelColorStyle } from '../workItemLabelColors'
import YpChoiceCards from '../../yp/YpChoiceCards.vue'
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
  describeResult,
  DUPLICATE_MODE_OPTIONS,
  emptyChoices,
  emptyMapping,
  fieldOfColumn,
  IMPORT_FIELD_LABELS,
  importableCount,
  importLimitIssues,
  BLANK_VALUE,
  buildImportRows,
  mappedOptionalFields,
  previewRows,
  valueMatch,
  valueOptions,
  type ImportPreviewRow,
  type OptionalImportField,
  type ValueMatch,
  mappedColumnCount,
  OPTIONAL_IMPORT_FIELDS,
  resolveAssignee,
  resolveContent,
  resolvePriority,
  resolveStatus,
  setChoice,
  type ImportField,
  type ImportOption,
  type ImportSettings,
  type ImportTable,
  type ValueKind,
} from './workItemImportModel'

const ElSelect = ElSelectRaw as unknown as DefineComponent
const ElOption = ElOptionRaw as unknown as DefineComponent

const props = defineProps<{
  modelValue: boolean
  projectId: string
  contents: readonly Content[]
  statuses: readonly WorkItemStatusLabel[]
  priorities: readonly WorkItemPriorityLabel[]
  members: readonly ProjectMember[]
  /** 创建默认值或第一个启用的类别，作为「导入为类别」的初始值。 */
  defaultContentId: string | undefined
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  imported: [result: WorkItemImportResult]
}>()

const STEPS = ['上传文件', '映射列与取值', '处理重复并预览'] as const
const NONE = '__none__'
const SKIP_COLUMN = ''

const step = ref(0)
const fileName = ref('')
const reading = ref(false)
const readError = ref('')
const table = shallowRef<ImportTable>()
const settings = ref<ImportSettings>({ mapping: emptyMapping(), defaultContentId: '', choices: emptyChoices() })
const duplicateMode = ref<WorkItemImportDuplicateMode>(WorkItemImportDuplicateMode.Skip)
const preview = ref<WorkItemImportResult>()
const previewing = ref(false)
const previewProblem = ref<ApiProblem>()
const submitting = ref(false)
const submitProblem = ref<ApiProblem>()
let previewSeq = 0
let readSeq = 0
let commitKey: string | undefined

const catalog = computed(() => buildImportCatalog({
  contents: props.contents, statuses: props.statuses, priorities: props.priorities, members: props.members,
}))
const headers = computed(() => table.value?.headers ?? [])
const mapping = computed(() => settings.value.mapping)
const mappedCount = computed(() => mappedColumnCount(mapping.value))
const unrecognizedDates = computed(() => table.value ? countUnrecognizedDueDates(table.value, mapping.value) : 0)
const mappingIssues = computed(() => table.value ? importLimitIssues(table.value, catalog.value, settings.value) : [])
const canNext = computed(() => Boolean(table.value) && canLeaveMapping(table.value!, catalog.value, settings.value))
const optionalTargets = OPTIONAL_IMPORT_FIELDS.map(field => ({ value: field, label: IMPORT_FIELD_LABELS[field] }))

const valueKinds = computed(() => {
  const kinds: { kind: ValueKind; field: ImportField; title: string }[] = [
    { kind: 'content', field: 'content', title: '类别' },
    { kind: 'status', field: 'status', title: '状态' },
    { kind: 'priority', field: 'priority', title: '优先级' },
    { kind: 'assignees', field: 'assignees', title: '处理人' },
  ]
  return kinds.filter(item => mapping.value[item.field] !== null)
})
const valueGroups = computed(() => valueKinds.value.map(item => ({
  ...item,
  values: collectValues(table.value!, mapping.value[item.field]!, item.kind)
    .map(value => ({ ...value, match: valueMatch(catalog.value, settings.value, item.kind, value.key) })),
})))

const rowCount = computed(() => table.value?.rows.length ?? 0)
const toImport = computed(() => preview.value ? importableCount(preview.value) : 0)
const previewStats = computed(() => preview.value ? ([
  [WorkItemImportOutcome.Create, preview.value.createdCount],
  [WorkItemImportOutcome.Update, preview.value.updatedCount],
  [WorkItemImportOutcome.Skip, preview.value.skippedCount],
  [WorkItemImportOutcome.Error, preview.value.failedCount],
] as const).map(([outcome, value]) => ({ outcome, value, ...OUTCOMES[outcome]! })) : [])
const canSubmit = computed(() => Boolean(preview.value) && !previewing.value && !submitting.value && toImport.value > 0)

const VALUE_RULES: Record<ValueKind, string> = {
  content: '名称或编码相同的自动匹配，匹配不到的使用「导入为类别」。',
  status: '名称或编码相同的自动匹配，匹配不到的默认为「未开始」。',
  priority: '名称或编码相同的自动匹配，匹配不到的留空。',
  assignees: '只在项目的有效成员里匹配。同名的成员需要手动选择，匹配不到的默认不分配。',
}
const MATCH_BADGES: Record<ValueMatch, { label: string; tone: string }> = {
  auto: { label: '已匹配', tone: 'green' },
  manual: { label: '已选择', tone: 'blue' },
  fallback: { label: '未匹配', tone: 'orange' },
  ambiguous: { label: '需选择', tone: 'red' },
}
const OUTCOMES: Partial<Record<WorkItemImportOutcome, { label: string; tone: string; icon: Component }>> = {
  [WorkItemImportOutcome.Create]: { label: '新增', tone: 'green', icon: CirclePlus },
  [WorkItemImportOutcome.Update]: { label: '更新', tone: 'blue', icon: RefreshRight },
  [WorkItemImportOutcome.Skip]: { label: '跳过', tone: 'gray', icon: Remove },
  [WorkItemImportOutcome.Error]: { label: '无法导入', tone: 'red', icon: CircleCloseFilled },
}
const MODE_ICONS: Record<Exclude<WorkItemImportDuplicateMode, WorkItemImportDuplicateMode.UnknownDefaultOpenApi>, Component> = {
  [WorkItemImportDuplicateMode.Create]: CirclePlus,
  [WorkItemImportDuplicateMode.Skip]: Remove,
  [WorkItemImportDuplicateMode.Update]: RefreshRight,
}
const modeOptions = DUPLICATE_MODE_OPTIONS.map(option => ({ ...option, icon: MODE_ICONS[option.value] }))

const outcomeFilter = ref<WorkItemImportOutcome | null>(null)
const issuesOnly = ref(false)
const mappedFields = computed(() => new Set<OptionalImportField>(mappedOptionalFields(mapping.value)))
const lookups = computed(() => {
  const by = (options: readonly ImportOption[]) => new Map(options.map(option => [option.value, option]))
  return { contents: by(catalog.value.contents), statuses: by(catalog.value.statuses), priorities: by(catalog.value.priorities), members: by(catalog.value.members) }
})
const valueSummary = computed(() => {
  const counts: Record<ValueMatch, number> = { manual: 0, auto: 0, fallback: 0, ambiguous: 0 }
  valueGroups.value.forEach(group => group.values.forEach(value => { counts[value.match] += 1 }))
  return counts
})
const previewList = computed(() => preview.value && table.value
  ? previewRows(buildImportRows(table.value, catalog.value, settings.value), preview.value) : [])
const issueCount = computed(() => previewList.value.filter(row => row.messages.length).length)
const visibleRows = computed(() => previewList.value.filter(row =>
  (!outcomeFilter.value || row.outcome === outcomeFilter.value) && (!issuesOnly.value || row.messages.length > 0)))
const previewColumns = computed(() => [
  '56px', 'minmax(180px, 1fr)', '112px', '112px',
  ...(mappedFields.value.has('priority') ? ['88px'] : []),
  ...(mappedFields.value.has('assignees') ? ['96px'] : []),
  ...(mappedFields.value.has('dueDate') ? ['104px'] : []),
  '88px',
].join(' '))

function samplesOf(column: number | null): string[] {
  const samples: string[] = []
  if (column === null) return samples
  for (const row of table.value?.rows ?? []) {
    const text = cellText(row.cells[column])
    if (text) samples.push(text)
    if (samples.length === 2) break
  }
  return samples
}

const optionOf = (kind: ValueKind, value: string | undefined): ImportOption | undefined =>
  valueOptions(catalog.value, kind).find(option => option.value === value)
const labelStyle = (option: ImportOption | undefined): Record<string, string> =>
  ({ ...workItemLabelColorStyle(option?.colorToken), color: 'var(--yp-text-inverse)' })
/** 更新行中未映射的类别和状态保持原值。 */
const keeps = (row: ImportPreviewRow, field: OptionalImportField): boolean =>
  row.outcome === WorkItemImportOutcome.Update && !mappedFields.value.has(field)
const assigneesOf = (row: ImportPreviewRow) =>
  row.assigneeUserIds.map(userId => ({ userId, displayName: lookups.value.members.get(userId)!.label }))
const isoDate = (date: Date): string => date.toISOString().slice(0, 10)
const toggleOutcome = (outcome: WorkItemImportOutcome): void => {
  outcomeFilter.value = outcomeFilter.value === outcome ? null : outcome
}

function reset(): void {
  step.value = 0
  fileName.value = ''
  reading.value = false
  readError.value = ''
  table.value = undefined
  settings.value = { mapping: emptyMapping(), defaultContentId: props.defaultContentId ?? catalog.value.contents[0]?.value ?? '', choices: emptyChoices() }
  duplicateMode.value = WorkItemImportDuplicateMode.Skip
  preview.value = undefined
  previewing.value = false
  previewProblem.value = undefined
  submitting.value = false
  submitProblem.value = undefined
  outcomeFilter.value = null
  issuesOnly.value = false
  previewSeq += 1
  readSeq += 1
  commitKey = undefined
}

watch(() => props.modelValue, open => { if (open) reset() }, { immediate: true })

async function onFile(file: File): Promise<void> {
  const seq = ++readSeq
  reading.value = true
  readError.value = ''
  table.value = undefined
  fileName.value = ''
  try {
    const built = buildImportTable(await readSpreadsheet(file))
    if (seq !== readSeq) return
    if (!built.ok) {
      readError.value = built.message
      return
    }
    table.value = built.table
    fileName.value = file.name
    settings.value = { ...settings.value, mapping: autoMapColumns(built.table.headers), choices: emptyChoices() }
  } catch (reason) {
    if (seq === readSeq) readError.value = reason instanceof SpreadsheetError ? reason.message : '无法读取该文件，请确认它是有效的表格文件。'
  } finally {
    if (seq === readSeq) reading.value = false
  }
}

function setMapping(next: ImportSettings['mapping']): void {
  settings.value = { ...settings.value, mapping: next }
}

const columnTarget = (column: number): string => fieldOfColumn(mapping.value, column) ?? SKIP_COLUMN
const setColumnTarget = (column: number, value: string): void =>
  setMapping(assignColumn(mapping.value, column, value === SKIP_COLUMN ? null : value as ImportField))
const setTitleColumn = (value: number | undefined | ''): void =>
  setMapping(assignField(mapping.value, 'title', typeof value === 'number' ? value : null))

/** 同名成员加上编号尾巴以便区分。 */
function optionLabel(kind: ValueKind, option: ImportOption): string {
  if (kind !== 'assignees') return option.label
  const twins = catalog.value.members.filter(member => member.label === option.label)
  return twins.length > 1 ? `${option.label}（…${option.value.slice(-4)}）` : option.label
}

/** 下拉框当前显示的值；同名未选定的处理人为 undefined，显示占位提示。 */
function currentChoice(kind: ValueKind, key: string): string | undefined {
  const config = settings.value
  if (kind === 'content') return resolveContent(catalog.value, config, key)
  if (kind === 'status') return resolveStatus(catalog.value, config, key)
  if (kind === 'priority') return resolvePriority(catalog.value, config, key) ?? NONE
  const resolved = resolveAssignee(catalog.value, config, key)
  return resolved.kind === 'ambiguous' ? undefined : resolved.userId ?? NONE
}

function candidatesFor(kind: ValueKind, key: string): ImportOption[] {
  if (kind !== 'assignees') return valueOptions(catalog.value, kind)
  const resolved = resolveAssignee(catalog.value, settings.value, key)
  return resolved.kind === 'ambiguous' ? resolved.candidates : catalog.value.members
}

const noneLabel = (kind: ValueKind): string => kind === 'priority' ? '不设置' : '不分配'

function choose(kind: ValueKind, key: string, value: string): void {
  settings.value = { ...settings.value, choices: setChoice(settings.value.choices, kind, key, value === NONE ? null : value) }
}

async function runPreview(): Promise<void> {
  if (!table.value) return
  const seq = ++previewSeq
  const csrf = readCsrfToken()
  preview.value = undefined
  if (!csrf) {
    previewProblem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  previewing.value = true
  previewProblem.value = undefined
  try {
    const result = await workItemsApi.importWorkItems({
      projectId: props.projectId,
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      workItemImportRequest: buildImportRequest(table.value, catalog.value, settings.value, duplicateMode.value, true),
    })
    if (seq === previewSeq) preview.value = result
  } catch (reason) {
    const problem = await toApiProblem(reason)
    if (seq === previewSeq) previewProblem.value = problem
  } finally {
    if (seq === previewSeq) previewing.value = false
  }
}

watch(step, value => {
  commitKey = undefined
  submitProblem.value = undefined
  if (value === 2) void runPreview()
})
watch(duplicateMode, () => {
  commitKey = undefined
  submitProblem.value = undefined
  if (step.value === 2) void runPreview()
})

async function submit(): Promise<void> {
  if (!canSubmit.value || !table.value) return
  const csrf = readCsrfToken()
  if (!csrf) {
    submitProblem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  commitKey ??= crypto.randomUUID()
  submitting.value = true
  submitProblem.value = undefined
  try {
    const result = await workItemsApi.importWorkItems({
      projectId: props.projectId,
      xXSRFTOKEN: csrf,
      idempotencyKey: commitKey,
      workItemImportRequest: buildImportRequest(table.value, catalog.value, settings.value, duplicateMode.value, false),
    })
    const message = describeResult(result)
    if (result.failedCount > 0) ElMessage.warning(message)
    else ElMessage.success(message)
    emit('imported', result)
    emit('update:modelValue', false)
  } catch (reason) {
    submitProblem.value = await toApiProblem(reason)
  } finally {
    submitting.value = false
  }
}

function close(done?: () => void): void {
  if (submitting.value) return
  done?.()
  emit('update:modelValue', false)
}

</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="work-item-import-dialog"
    modal-class="work-item-import-overlay"
    width="min(960px, calc(100vw - 32px))"
    align-center
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    :before-close="close"
  >
    <template #header="{ titleId }">
      <h2
        :id="titleId"
        class="wi-import__title"
      >
        导入工作项
      </h2>
      <import-steps
        class="wi-import__steps"
        :steps="STEPS"
        :current="step"
      />
    </template>

    <div class="wi-import">
      <template v-if="step === 0">
        <import-file-drop
          hint="拖入文件或点击选择 · 支持 .csv、.xlsx、.xls，最大 5 MB、2000 行"
          :disabled="reading"
          @file="onFile"
        />
        <p
          v-if="reading"
          class="wi-caption"
          role="status"
        >
          正在读取文件…
        </p>
        <yp-notice
          v-if="readError"
          tone="danger"
        >
          {{ readError }}
        </yp-notice>
        <yp-notice
          v-else-if="table && !reading"
          tone="success"
          :title="`已读取「${fileName}」`"
        >
          <strong>{{ rowCount }}</strong> 行数据，<strong>{{ headers.length }}</strong> 列。第一行非空行作为表头，可重新选择文件替换。
        </yp-notice>
      </template>

      <template v-else-if="step === 1 && table">
        <yp-notice>
          工时不在这里导入，请到 <strong>团队视图 → 成员工时 → 迁入工时</strong> 导入。
        </yp-notice>

        <section
          class="wi-section"
          aria-labelledby="wi-columns-title"
        >
          <header class="wi-section__head">
            <span
              class="wi-section__index"
              aria-hidden="true"
            >1</span>
            <div class="wi-section__copy">
              <h3 id="wi-columns-title">
                列映射
              </h3>
              <p class="wi-rule">
                为文件中的每一列选择导入到哪个字段，每个字段只能对应一列；「完成工时」等工时列不能导入。
              </p>
            </div>
            <div
              class="wi-stat-chips"
              aria-label="列映射统计"
            >
              <span class="wi-stat-chip wi-stat-chip--blue">已映射 <b>{{ mappedCount }}</b></span>
              <span class="wi-stat-chip">不导入 <b>{{ headers.length - mappedCount }}</b></span>
            </div>
          </header>
          <div
            class="wi-map"
            role="table"
            aria-label="列映射"
          >
            <div
              class="wi-map__row wi-map__row--head"
              role="row"
            >
              <span role="columnheader">文件中的列</span>
              <span role="columnheader">示例数据</span>
              <span aria-hidden="true" />
              <span role="columnheader">导入为</span>
            </div>
            <div
              class="wi-map__row wi-map__row--title"
              role="row"
            >
              <span role="cell">
                <el-select
                  :model-value="mapping.title ?? undefined"
                  clearable
                  placeholder="选择标题所在的列"
                  aria-label="标题所在的列"
                  @update:model-value="setTitleColumn"
                >
                  <el-option
                    v-for="(header, index) in headers"
                    :key="index"
                    :label="header"
                    :value="index"
                  />
                </el-select>
              </span>
              <span
                class="wi-samples"
                role="cell"
              >
                <span
                  v-for="(sample, sampleIndex) in samplesOf(mapping.title)"
                  :key="sampleIndex"
                  class="wi-sample"
                >{{ sample }}</span>
              </span>
              <el-icon
                class="wi-map__arrow"
                aria-hidden="true"
              ><right /></el-icon>
              <span
                class="wi-map__target"
                role="cell"
              >
                <span class="wi-field-chip">标题</span>
                <span class="wi-badge wi-badge--red">必填</span>
              </span>
            </div>
            <template
              v-for="(header, index) in headers"
              :key="index"
            >
              <div
                v-if="index !== mapping.title"
                class="wi-map__row"
                :class="{ 'is-skipped': columnTarget(index) === SKIP_COLUMN }"
                role="row"
              >
                <span role="cell">
                  <span
                    class="wi-column-chip"
                    :title="header"
                  >{{ header }}</span>
                </span>
                <span
                  class="wi-samples"
                  role="cell"
                >
                  <span
                    v-for="(sample, sampleIndex) in samplesOf(index)"
                    :key="sampleIndex"
                    class="wi-sample"
                  >{{ sample }}</span>
                </span>
                <el-icon
                  class="wi-map__arrow"
                  aria-hidden="true"
                ><right /></el-icon>
                <span role="cell">
                  <el-select
                    :model-value="columnTarget(index)"
                    :aria-label="`${header}导入为`"
                    @update:model-value="setColumnTarget(index, $event)"
                  >
                    <el-option
                      v-for="target in optionalTargets"
                      :key="target.value"
                      :label="target.label"
                      :value="target.value"
                    />
                    <el-option
                      label="不导入"
                      :value="SKIP_COLUMN"
                    />
                  </el-select>
                </span>
              </div>
            </template>
          </div>
          <yp-notice
            v-if="unrecognizedDates > 0"
            tone="warning"
            size="small"
          >
            <strong>{{ unrecognizedDates }}</strong> 行截止日期无法识别，导入后留空。
          </yp-notice>
          <yp-notice
            v-if="mappingIssues.length"
            tone="danger"
            :title="`有 ${mappingIssues.length} 处超出导入上限，请调整文件或取消对应列的映射`"
          >
            <ul>
              <li
                v-for="issue in mappingIssues.slice(0, 20)"
                :key="issue"
              >
                {{ issue }}
              </li>
            </ul>
          </yp-notice>
        </section>

        <section
          class="wi-section"
          aria-labelledby="wi-values-title"
        >
          <header class="wi-section__head">
            <span
              class="wi-section__index"
              aria-hidden="true"
            >2</span>
            <div class="wi-section__copy">
              <h3 id="wi-values-title">
                取值映射
              </h3>
              <p class="wi-rule">
                把文件里的取值对应到项目中的值。已按名称或编码自动匹配，请重点检查「未匹配」和「需选择」的取值。
              </p>
            </div>
            <div
              v-if="valueGroups.length"
              class="wi-stat-chips"
              aria-label="取值映射统计"
            >
              <span class="wi-stat-chip wi-stat-chip--green">已匹配 <b>{{ valueSummary.auto + valueSummary.manual }}</b></span>
              <span class="wi-stat-chip wi-stat-chip--orange">未匹配 <b>{{ valueSummary.fallback }}</b></span>
              <span
                v-if="valueSummary.ambiguous"
                class="wi-stat-chip wi-stat-chip--red"
              >需选择 <b>{{ valueSummary.ambiguous }}</b></span>
            </div>
          </header>

          <div class="wi-field-card wi-field-card--inline">
            <div class="wi-field-card__copy">
              <span class="wi-field-chip">导入为类别</span>
              <p class="wi-rule">
                {{ mapping.content === null ? '所有工作项都使用这个类别。' : '文件里的类别匹配不到时使用这个类别。' }}
              </p>
            </div>
            <el-select
              v-model="settings.defaultContentId"
              aria-label="导入为类别"
            >
              <template #prefix>
                <span
                  class="wi-dot"
                  :style="workItemLabelColorStyle(lookups.contents.get(settings.defaultContentId)?.colorToken)"
                />
              </template>
              <el-option
                v-for="option in catalog.contents"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              >
                <span class="wi-option"><span
                  class="wi-dot"
                  :style="workItemLabelColorStyle(option.colorToken)"
                />{{ option.label }}</span>
              </el-option>
            </el-select>
          </div>

          <article
            v-for="group in valueGroups"
            :key="group.kind"
            class="wi-field-card"
          >
            <header class="wi-field-card__head">
              <span class="wi-field-chip">{{ group.title }}</span>
              <span class="wi-meta">{{ group.values.length }} 个取值</span>
              <p class="wi-rule">
                {{ VALUE_RULES[group.kind] }}
              </p>
            </header>
            <ul class="wi-values__list">
              <li
                v-for="value in group.values"
                :key="value.key"
                class="wi-values__item"
                :class="`is-${value.match}`"
              >
                <span class="wi-values__source">
                  <span
                    class="wi-value-chip"
                    :class="{ 'is-blank': value.key === BLANK_VALUE }"
                    :title="value.label"
                  >{{ value.label }}</span>
                  <span class="wi-meta">{{ value.count }} 行</span>
                </span>
                <el-icon
                  class="wi-map__arrow"
                  aria-hidden="true"
                ><right /></el-icon>
                <el-select
                  :model-value="currentChoice(group.kind, value.key)"
                  :class="{ 'is-unresolved': value.match === 'ambiguous' }"
                  :placeholder="value.match === 'ambiguous' ? '同名成员，请选择' : ''"
                  :aria-label="`${value.label}对应的${group.title}`"
                  @update:model-value="choose(group.kind, value.key, $event)"
                >
                  <template
                    v-if="group.kind !== 'assignees'"
                    #prefix
                  >
                    <span
                      class="wi-dot"
                      :style="workItemLabelColorStyle(optionOf(group.kind, currentChoice(group.kind, value.key))?.colorToken)"
                    />
                  </template>
                  <el-option
                    v-if="group.kind === 'priority' || group.kind === 'assignees'"
                    :label="noneLabel(group.kind)"
                    :value="NONE"
                  />
                  <el-option
                    v-for="option in candidatesFor(group.kind, value.key)"
                    :key="option.value"
                    :label="optionLabel(group.kind, option)"
                    :value="option.value"
                  >
                    <span class="wi-option"><span
                      v-if="group.kind !== 'assignees'"
                      class="wi-dot"
                      :style="workItemLabelColorStyle(option.colorToken)"
                    />{{ optionLabel(group.kind, option) }}</span>
                  </el-option>
                </el-select>
                <span
                  class="wi-badge"
                  :class="`wi-badge--${MATCH_BADGES[value.match].tone}`"
                >{{ MATCH_BADGES[value.match].label }}</span>
              </li>
            </ul>
          </article>
        </section>
      </template>

      <template v-else-if="step === 2">
        <section
          class="wi-section"
          aria-labelledby="wi-mode-title"
        >
          <header class="wi-section__head">
            <span
              class="wi-section__index"
              aria-hidden="true"
            >1</span>
            <div class="wi-section__copy">
              <h3 id="wi-mode-title">
                重复工作项
              </h3>
              <p class="wi-rule">
                按标题匹配项目中未删除、未归档的工作项。
              </p>
            </div>
          </header>
          <yp-choice-cards
            v-model="duplicateMode"
            class="wi-modes"
            :options="modeOptions"
            label="重复工作项的处理方式"
            :disabled="submitting"
          />
          <yp-notice
            v-if="duplicateMode === WorkItemImportDuplicateMode.Update"
            tone="warning"
            size="small"
          >
            已映射列里的<strong>空单元格会清空</strong>同名工作项原有的值；只想改部分字段时，把其他列设为「不导入」。
          </yp-notice>
        </section>

        <section
          class="wi-section"
          aria-labelledby="wi-preview-title"
        >
          <header class="wi-section__head">
            <span
              class="wi-section__index"
              aria-hidden="true"
            >2</span>
            <div class="wi-section__copy">
              <h3 id="wi-preview-title">
                预览
              </h3>
              <p class="wi-rule">
                点击统计只看对应结果的行；带说明的行列出了处理方式或无法导入的原因。
              </p>
            </div>
          </header>
          <inline-problem
            v-if="previewProblem"
            :problem="previewProblem"
          />
          <p
            v-else-if="previewing"
            class="wi-caption"
            role="status"
          >
            正在预览导入结果…
          </p>
          <template v-else-if="preview">
            <div
              class="wi-stats"
              role="group"
              aria-label="预览统计"
            >
              <button
                v-for="stat in previewStats"
                :key="stat.outcome"
                type="button"
                class="wi-stat"
                :class="[`wi-tone--${stat.tone}`, { 'is-active': outcomeFilter === stat.outcome }]"
                :aria-pressed="outcomeFilter === stat.outcome"
                @click="toggleOutcome(stat.outcome)"
              >
                <el-icon
                  class="wi-stat__icon"
                  aria-hidden="true"
                ><component :is="stat.icon" /></el-icon>
                <span class="wi-stat__label">{{ stat.label }}</span>
                <b class="wi-stat__value">{{ stat.value }}</b>
              </button>
            </div>
            <div
              class="wi-preview"
              :style="{ '--wi-preview-columns': previewColumns }"
            >
              <div class="wi-preview__toolbar">
                <span class="wi-meta">显示 <b>{{ visibleRows.length }}</b> / {{ previewList.length }} 行</span>
                <el-checkbox
                  v-model="issuesOnly"
                  :disabled="!issueCount"
                >
                  只看带说明的行（{{ issueCount }}）
                </el-checkbox>
              </div>
              <div
                class="wi-preview__table"
                role="table"
                aria-label="导入预览"
              >
                <div
                  class="wi-preview__row wi-preview__row--head"
                  role="row"
                >
                  <span role="columnheader">行号</span>
                  <span role="columnheader">标题</span>
                  <span role="columnheader">类别</span>
                  <span role="columnheader">状态</span>
                  <span
                    v-if="mappedFields.has('priority')"
                    role="columnheader"
                  >优先级</span>
                  <span
                    v-if="mappedFields.has('assignees')"
                    role="columnheader"
                  >处理人</span>
                  <span
                    v-if="mappedFields.has('dueDate')"
                    role="columnheader"
                  >截止日期</span>
                  <span role="columnheader">结果</span>
                </div>
                <div
                  v-for="row in visibleRows"
                  :key="row.rowNumber"
                  class="wi-preview__group"
                  :class="`is-${row.outcome.toLowerCase()}`"
                  role="rowgroup"
                >
                  <div
                    class="wi-preview__row"
                    role="row"
                  >
                    <span
                      class="wi-preview__no"
                      role="cell"
                    >{{ row.rowNumber }}</span>
                    <span
                      class="wi-preview__title"
                      role="cell"
                      :title="row.parentTitle ? `父工作项：${row.parentTitle}` : row.title"
                    >
                      <span
                        v-if="row.parentTitle"
                        class="wi-preview__branch"
                        aria-hidden="true"
                      />{{ row.title || '(无标题)' }}
                    </span>
                    <span
                      class="wi-preview__cell"
                      role="cell"
                    >
                      <span
                        v-if="keeps(row, 'content')"
                        class="wi-keep"
                      >不变</span>
                      <span
                        v-else
                        class="wi-pill"
                        :style="labelStyle(lookups.contents.get(row.contentId))"
                      >{{ lookups.contents.get(row.contentId)?.label }}</span>
                    </span>
                    <span
                      v-if="keeps(row, 'status')"
                      class="wi-preview__cell"
                      role="cell"
                    ><span class="wi-keep">不变</span></span>
                    <span
                      v-else
                      class="wi-preview__block"
                      role="cell"
                      :style="labelStyle(lookups.statuses.get(row.statusCode))"
                    >{{ lookups.statuses.get(row.statusCode)?.label }}</span>
                    <span
                      v-if="mappedFields.has('priority')"
                      class="wi-preview__block"
                      :class="{ 'is-empty': !row.priority }"
                      role="cell"
                      :style="row.priority ? labelStyle(lookups.priorities.get(row.priority)) : undefined"
                    >{{ row.priority ? lookups.priorities.get(row.priority)?.label : '—' }}</span>
                    <span
                      v-if="mappedFields.has('assignees')"
                      class="wi-preview__cell"
                      role="cell"
                    >
                      <yp-assignee-stack
                        v-if="row.assigneeUserIds.length"
                        :assignees="assigneesOf(row)"
                      />
                      <span
                        v-else
                        class="wi-keep"
                      >—</span>
                    </span>
                    <span
                      v-if="mappedFields.has('dueDate')"
                      class="wi-preview__cell wi-preview__date"
                      role="cell"
                    >{{ row.dueDate ? isoDate(row.dueDate) : '—' }}</span>
                    <span
                      class="wi-preview__cell"
                      role="cell"
                    >
                      <span
                        class="wi-badge"
                        :class="`wi-badge--${OUTCOMES[row.outcome]!.tone}`"
                      >{{ OUTCOMES[row.outcome]!.label }}</span>
                    </span>
                  </div>
                  <div
                    v-if="row.messages.length"
                    class="wi-preview__issue"
                    role="row"
                  >
                    <span role="cell">
                      <el-icon aria-hidden="true"><component :is="row.outcome === WorkItemImportOutcome.Error ? CircleCloseFilled : WarningFilled" /></el-icon>{{ row.messages.join('；') }}
                    </span>
                  </div>
                </div>
                <p
                  v-if="!visibleRows.length"
                  class="wi-preview__empty"
                >
                  没有符合筛选的行
                </p>
              </div>
            </div>
          </template>
          <inline-problem
            v-if="submitProblem"
            :problem="submitProblem"
          />
        </section>
      </template>
    </div>

    <template #footer>
      <el-button
        :disabled="submitting"
        @click="close()"
      >
        取消
      </el-button>
      <el-button
        v-if="step > 0"
        :disabled="submitting"
        @click="step -= 1"
      >
        上一步
      </el-button>
      <el-button
        v-if="step < 2"
        type="primary"
        :disabled="step === 0 ? !table || reading : !canNext"
        @click="step += 1"
      >
        下一步
      </el-button>
      <el-button
        v-else
        type="primary"
        :disabled="!canSubmit"
        :loading="submitting"
        @click="submit"
      >
        导入 {{ toImport }} 项
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.work-item-import-overlay) { background: var(--yp-overlay); }
:global(.work-item-import-dialog.el-dialog) { padding: var(--yp-space-6); border-radius: var(--yp-radius-xl); background: var(--yp-bg-raised); box-shadow: var(--yp-shadow-overlay); }
:global(.work-item-import-dialog .el-dialog__header) { padding: 0 0 var(--yp-space-4); }
:global(.work-item-import-dialog .el-dialog__body) { max-height: min(66vh, 640px); overflow-y: auto; }
:global(.work-item-import-dialog .el-dialog__footer) { display: flex; justify-content: flex-end; gap: var(--yp-space-2); padding-top: var(--yp-space-5); }

.wi-import__title { margin: 0 0 var(--yp-space-3); color: var(--yp-text-primary); font: 600 var(--yp-type-section-title-size) / var(--yp-type-section-title-line) var(--yp-font-heading); }
.wi-import { display: grid; gap: var(--yp-space-5); font-size: var(--yp-type-body-size); }

/* 色调：徽标、统计与预览共用 */
.wi-tone--green, .wi-badge--green, .wi-stat-chip--green { --wi-tone: var(--yp-status-green); }
.wi-tone--blue, .wi-badge--blue, .wi-stat-chip--blue { --wi-tone: var(--yp-status-blue); }
.wi-tone--gray, .wi-badge--gray { --wi-tone: var(--yp-status-gray); }
.wi-tone--red, .wi-badge--red, .wi-stat-chip--red { --wi-tone: var(--yp-status-red); }
.wi-badge--orange, .wi-stat-chip--orange { --wi-tone: var(--yp-status-orange); }

/* 文字角色 */
.wi-caption { margin: 0; color: var(--yp-text-secondary); }
.wi-rule { margin: 0; color: var(--yp-text-secondary); font-size: var(--yp-type-caption-size); line-height: var(--yp-type-caption-line); }
.wi-meta { color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); font-variant-numeric: tabular-nums; white-space: nowrap; }
.wi-meta b { color: var(--yp-text-primary); font-weight: 600; }
.wi-column-chip { display: inline-block; max-width: 100%; padding: 2px var(--yp-space-2); overflow: hidden; border: 1px solid var(--yp-border-default); border-radius: var(--yp-radius-xs); color: var(--yp-text-primary); background: var(--yp-bg-sunken); font-weight: 600; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }
.wi-field-chip { display: inline-flex; flex: none; align-items: center; padding: 2px var(--yp-space-2); border-radius: var(--yp-radius-xs); color: var(--yp-link); background: color-mix(in srgb, var(--yp-action-primary) 10%, var(--yp-bg-surface)); font-weight: 600; white-space: nowrap; }
.wi-sample, .wi-value-chip { display: inline-block; max-width: 100%; padding: 1px var(--yp-space-2); overflow: hidden; border-radius: var(--yp-radius-pill); background: var(--yp-bg-hover); text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }
.wi-sample { max-width: 48%; color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.wi-value-chip { color: var(--yp-text-primary); }
.wi-value-chip.is-blank { color: var(--yp-text-muted); font-style: italic; }
.wi-badge { display: inline-flex; height: 20px; flex: none; align-items: center; justify-content: center; padding: 0 6px; border: 1px solid color-mix(in srgb, var(--wi-tone) var(--yp-status-border-mix), var(--yp-border-subtle)); border-radius: var(--yp-radius-xs); color: color-mix(in srgb, var(--wi-tone) 62%, var(--yp-text-primary)); background: color-mix(in srgb, var(--wi-tone) var(--yp-status-surface-mix), var(--yp-bg-surface)); font-size: 11px; font-weight: 600; white-space: nowrap; }
.wi-stat-chips { display: flex; flex-wrap: wrap; gap: var(--yp-space-2); margin-left: auto; }
.wi-stat-chip { padding: 2px 10px; border-radius: var(--yp-radius-pill); color: var(--yp-text-secondary); background: var(--yp-bg-sunken); font-size: var(--yp-type-caption-size); white-space: nowrap; }
.wi-stat-chip b { margin-left: 2px; color: var(--yp-text-primary); font-variant-numeric: tabular-nums; font-weight: 700; }
.wi-stat-chip[class*='wi-stat-chip--'] b { color: color-mix(in srgb, var(--wi-tone) 62%, var(--yp-text-primary)); }
.wi-dot { display: inline-block; width: 8px; height: 8px; flex: none; border-radius: 50%; }
.wi-option { display: inline-flex; align-items: center; gap: var(--yp-space-2); }

/* 小节 */
.wi-section { display: grid; gap: var(--yp-space-3); }
.wi-section__head { display: flex; flex-wrap: wrap; align-items: flex-start; gap: var(--yp-space-3); }
.wi-section__index { display: inline-flex; width: 20px; height: 20px; flex: none; align-items: center; justify-content: center; margin-top: 1px; border-radius: var(--yp-radius-pill); color: var(--yp-status-blue-foreground); background: var(--yp-action-primary); font-size: 12px; font-weight: 700; }
.wi-section__copy { display: grid; flex: 1 1 280px; gap: 2px; }
.wi-section__copy h3 { margin: 0; color: var(--yp-text-primary); font-size: 14px; font-weight: 600; line-height: 22px; }

/* 列映射 */
.wi-map { display: grid; overflow: hidden; border: 1px solid var(--yp-border-default); border-radius: var(--yp-radius-md); }
.wi-map__row { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1.4fr) 16px minmax(0, 1fr); align-items: center; gap: var(--yp-space-3); min-height: 44px; padding: var(--yp-space-1) var(--yp-space-3); border-top: 1px solid var(--yp-border-subtle); }
.wi-map__row:first-child { border-top: 0; }
.wi-map__row--head { min-height: 32px; color: var(--yp-text-secondary); background: var(--yp-bg-sunken); font-size: var(--yp-type-caption-size); }
.wi-map__row--title { background: color-mix(in srgb, var(--yp-action-primary) 6%, var(--yp-bg-surface)); box-shadow: inset 3px 0 0 var(--yp-action-primary); }
.wi-map__row.is-skipped .wi-column-chip, .wi-map__row.is-skipped .wi-samples { opacity: .55; }
.wi-map__arrow { color: var(--yp-text-disabled); }
.wi-map__row:not(.is-skipped):not(.wi-map__row--head) .wi-map__arrow { color: var(--yp-link); }
.wi-map__target { display: flex; align-items: center; gap: var(--yp-space-2); }
.wi-samples { display: flex; min-width: 0; gap: var(--yp-space-1); }

/* 取值映射 */
.wi-field-card { display: grid; gap: var(--yp-space-2); padding: var(--yp-space-3) var(--yp-space-4); border: 1px solid var(--yp-border-default); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.wi-field-card--inline { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: center; gap: var(--yp-space-3); }
.wi-field-card__copy { display: grid; gap: var(--yp-space-1); justify-items: start; }
.wi-field-card__head { display: flex; flex-wrap: wrap; align-items: center; gap: var(--yp-space-2); }
.wi-field-card__head .wi-rule { flex-basis: 100%; }
.wi-values__list { display: grid; margin: 0; padding: 0; gap: 2px; list-style: none; }
.wi-values__item { display: grid; grid-template-columns: minmax(0, 1.2fr) 16px minmax(0, 1fr) 56px; align-items: center; gap: var(--yp-space-3); padding: var(--yp-space-1) var(--yp-space-2); border-radius: var(--yp-radius-sm); }
.wi-values__item.is-fallback { background: color-mix(in srgb, var(--yp-status-orange) 7%, transparent); }
.wi-values__item.is-ambiguous { background: color-mix(in srgb, var(--yp-status-red) 7%, transparent); }
.wi-values__source { display: flex; min-width: 0; align-items: center; gap: var(--yp-space-2); }
.wi-values__item :deep(.is-unresolved .el-select__wrapper) { box-shadow: 0 0 0 1px var(--yp-status-red) inset; }

/* 处理方式 */
.wi-import .wi-modes { grid-template-columns: repeat(3, minmax(0, 1fr)); }

/* 预览统计 */
.wi-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--yp-space-3); }
.wi-stat { display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 2px var(--yp-space-2); padding: var(--yp-space-3) var(--yp-space-4); border: 1px solid color-mix(in srgb, var(--wi-tone) var(--yp-status-border-mix), var(--yp-border-subtle)); border-radius: var(--yp-radius-md); color: var(--yp-text-secondary); background: linear-gradient(135deg, color-mix(in srgb, var(--wi-tone) var(--yp-status-surface-mix), var(--yp-bg-surface)), var(--yp-bg-surface)); font: inherit; text-align: left; cursor: pointer; transition: box-shadow var(--yp-motion-fast) var(--yp-ease-standard); }
.wi-stat:hover { box-shadow: var(--yp-shadow-hover); }
.wi-stat.is-active { box-shadow: 0 0 0 2px var(--wi-tone); }
.wi-stat:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: 2px; }
.wi-stat__icon { color: var(--wi-tone); font-size: 16px; }
.wi-stat__label { font-size: var(--yp-type-caption-size); }
.wi-stat__value { grid-column: 1 / -1; color: color-mix(in srgb, var(--wi-tone) 62%, var(--yp-text-primary)); font-size: 22px; font-variant-numeric: tabular-nums; font-weight: 700; line-height: 28px; }

/* 预览表：与工作项表格一致的 36px 行、网格线与整格色块 */
.wi-preview { display: grid; gap: var(--yp-space-2); }
.wi-preview__toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--yp-space-3); }
.wi-preview__table { max-height: 360px; overflow: auto; border: 1px solid var(--yp-monday-grid-border); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); font-size: 13px; }
.wi-preview__row { display: grid; min-width: 720px; grid-template-columns: var(--wi-preview-columns); align-items: stretch; height: 36px; border-bottom: 1px solid var(--yp-monday-grid-border); }
.wi-preview__row > * { display: flex; min-width: 0; align-items: center; border-right: 1px solid var(--yp-monday-grid-border); }
.wi-preview__row > :last-child { border-right: 0; }
.wi-preview__row--head { position: sticky; top: 0; z-index: 1; color: var(--yp-text-secondary); background: var(--yp-table-header-bg); font-weight: 500; }
.wi-preview__row--head > * { justify-content: center; padding: 0 var(--yp-space-2); }
.wi-preview__group:hover .wi-preview__row { background: var(--yp-bg-sunken); }
.wi-preview__no { justify-content: center; color: var(--yp-text-muted); font-variant-numeric: tabular-nums; }
.wi-preview__title { gap: var(--yp-space-1); padding: 0 var(--yp-space-3); overflow: hidden; color: var(--yp-text-primary); text-overflow: ellipsis; white-space: nowrap; }
.wi-preview__branch { width: 10px; height: 10px; flex: none; margin: -6px 2px 0 6px; border-bottom: 1.5px solid var(--yp-border-strong); border-left: 1.5px solid var(--yp-border-strong); border-bottom-left-radius: 4px; }
.wi-preview__cell { justify-content: center; padding: 0 var(--yp-space-2); }
.wi-preview__date { color: var(--yp-text-secondary); font-variant-numeric: tabular-nums; }
.wi-preview__block { justify-content: center; padding: 0 var(--yp-space-2); overflow: hidden; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.wi-preview__block.is-empty { color: var(--yp-priority-empty-foreground); background: var(--yp-priority-empty); }
.wi-pill { max-width: 100%; height: 22px; padding: 0 var(--yp-space-3); overflow: hidden; border-radius: var(--yp-radius-pill); line-height: 22px; text-overflow: ellipsis; white-space: nowrap; }
.wi-keep { color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.wi-preview__group.is-skip .wi-preview__row > :not(:last-child),
.wi-preview__group.is-error .wi-preview__row > :not(:last-child) { opacity: .5; }
.wi-preview__issue { min-width: 720px; padding: var(--yp-space-1) var(--yp-space-3) 6px 68px; border-bottom: 1px solid var(--yp-monday-grid-border); color: color-mix(in srgb, var(--yp-status-orange) 62%, var(--yp-text-primary)); background: color-mix(in srgb, var(--yp-status-orange) 6%, var(--yp-bg-surface)); font-size: var(--yp-type-caption-size); line-height: var(--yp-type-caption-line); }
.wi-preview__issue .el-icon { margin-right: var(--yp-space-1); vertical-align: -2px; }
.wi-preview__group.is-error .wi-preview__issue { color: color-mix(in srgb, var(--yp-status-red) 62%, var(--yp-text-primary)); background: color-mix(in srgb, var(--yp-status-red) 6%, var(--yp-bg-surface)); }
.wi-preview__group.is-skip .wi-preview__issue { color: var(--yp-text-secondary); background: var(--yp-bg-sunken); }
.wi-preview__empty { margin: 0; padding: var(--yp-space-6); color: var(--yp-text-muted); text-align: center; }

@media (max-width: 640px) {
  .wi-map__row, .wi-values__item, .wi-field-card--inline { grid-template-columns: minmax(0, 1fr); }
  .wi-map__arrow { display: none; }
  .wi-import .wi-modes { grid-template-columns: minmax(0, 1fr); }
  .wi-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
