<script setup lang="ts">
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
  ElAlert,
  ElButton,
  ElDialog,
  ElMessage,
  ElOption as ElOptionRaw,
  ElSelect as ElSelectRaw,
} from 'element-plus'
import { computed, ref, shallowRef, watch, type DefineComponent } from 'vue'
import { workItemsApi } from '../../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../../api/problems'
import InlineProblem from '../../InlineProblem.vue'
import ImportFileDrop from '../../import/ImportFileDrop.vue'
import ImportSteps from '../../import/ImportSteps.vue'
import { cellText, readSpreadsheet, SpreadsheetError } from '../../import/spreadsheet'
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
  issueRows,
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
const showIssues = ref(false)
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
  values: collectValues(table.value!, mapping.value[item.field]!, item.kind),
})))

const rowCount = computed(() => table.value?.rows.length ?? 0)
const previewIssues = computed(() => preview.value && table.value ? issueRows(preview.value, table.value, mapping.value) : [])
const toImport = computed(() => preview.value ? importableCount(preview.value) : 0)
const previewStats = computed(() => preview.value ? [
  { label: '新增', value: preview.value.createdCount, failed: false },
  { label: '更新', value: preview.value.updatedCount, failed: false },
  { label: '跳过', value: preview.value.skippedCount, failed: false },
  { label: '无法导入', value: preview.value.failedCount, failed: true },
] : [])
const canSubmit = computed(() => Boolean(preview.value) && !previewing.value && !submitting.value && toImport.value > 0)

function sampleOf(column: number): string {
  const samples: string[] = []
  for (const row of table.value?.rows ?? []) {
    const text = cellText(row.cells[column])
    if (text) samples.push(text)
    if (samples.length === 2) break
  }
  return samples.join(' · ')
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
  showIssues.value = false
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

function optionsOf(kind: ValueKind): ImportOption[] {
  return kind === 'content' ? catalog.value.contents : kind === 'status' ? catalog.value.statuses
    : kind === 'priority' ? catalog.value.priorities : catalog.value.members
}

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
  if (kind !== 'assignees') return optionsOf(kind)
  const resolved = resolveAssignee(catalog.value, settings.value, key)
  return resolved.kind === 'ambiguous' ? resolved.candidates : catalog.value.members
}

const noneLabel = (kind: ValueKind): string => kind === 'priority' ? '不设置' : '不分配'

function choose(kind: ValueKind, key: string, value: string): void {
  settings.value = { ...settings.value, choices: setChoice(settings.value.choices, kind, key, value === NONE ? null : value) }
}

function isAmbiguous(kind: ValueKind, key: string): boolean {
  return kind === 'assignees' && resolveAssignee(catalog.value, settings.value, key).kind === 'ambiguous'
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

const outcomeLabel = (outcome: WorkItemImportOutcome): string => outcome === WorkItemImportOutcome.Error ? '无法导入' : '提示'
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="work-item-import-dialog"
    modal-class="work-item-import-overlay"
    width="min(860px, calc(100vw - 32px))"
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
          class="wi-import__note"
          role="status"
        >
          正在读取文件…
        </p>
        <p
          v-if="readError"
          class="wi-import__error"
          role="alert"
        >
          {{ readError }}
        </p>
        <p
          v-else-if="table && !reading"
          class="wi-import__loaded"
          role="status"
        >
          已读取「{{ fileName }}」：{{ rowCount }} 行数据，{{ headers.length }} 列。第一行非空行作为表头，可重新选择文件替换。
        </p>
      </template>

      <template v-else-if="step === 1 && table">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="工时不在这里导入，请在 团队视图 → 成员工时 → 迁入工时 中导入"
        />
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
            <span role="columnheader">示例</span>
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
              class="wi-map__sample"
              role="cell"
            >{{ mapping.title === null ? '' : sampleOf(mapping.title) }}</span>
            <span
              class="wi-map__target"
              role="cell"
            >标题<em>必填</em></span>
          </div>
          <template
            v-for="(header, index) in headers"
            :key="index"
          >
            <div
              v-if="index !== mapping.title"
              class="wi-map__row"
              role="row"
            >
              <span
                class="wi-map__name"
                role="cell"
              >{{ header }}</span>
              <span
                class="wi-map__sample"
                role="cell"
              >{{ sampleOf(index) }}</span>
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
        <p class="wi-import__note">
          已映射 {{ mappedCount }} · 不导入 {{ headers.length - mappedCount }}
        </p>
        <p
          v-if="unrecognizedDates > 0"
          class="wi-import__warn"
          role="status"
        >
          {{ unrecognizedDates }} 行截止日期无法识别，将留空
        </p>
        <el-alert
          v-if="mappingIssues.length"
          type="error"
          :closable="false"
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
        </el-alert>

        <section class="wi-values">
          <h3>导入为类别</h3>
          <p class="wi-values__hint">
            {{ mapping.content === null ? '所有工作项都使用这个类别。' : '文件里的类别匹配不到时使用这个类别。' }}
          </p>
          <el-select
            v-model="settings.defaultContentId"
            aria-label="导入为类别"
          >
            <el-option
              v-for="option in catalog.contents"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </section>

        <section
          v-for="group in valueGroups"
          :key="group.kind"
          class="wi-values"
        >
          <h3>{{ group.title }}取值</h3>
          <p class="wi-values__hint">
            <template v-if="group.kind === 'content'">
              名称或编码相同的自动匹配，匹配不到的使用「导入为类别」。
            </template>
            <template v-else-if="group.kind === 'status'">
              名称或编码相同的自动匹配，匹配不到的默认为「未开始」。
            </template>
            <template v-else-if="group.kind === 'priority'">
              名称或编码相同的自动匹配，匹配不到的留空。
            </template>
            <template v-else>
              只在项目的有效成员里匹配。同名的成员需要手动选择，匹配不到的默认不分配。
            </template>
          </p>
          <ul class="wi-values__list">
            <li
              v-for="value in group.values"
              :key="value.key"
              class="wi-values__item"
            >
              <span class="wi-values__label">{{ value.label }}<small>{{ value.count }} 行</small></span>
              <el-select
                :model-value="currentChoice(group.kind, value.key)"
                :class="{ 'is-unresolved': isAmbiguous(group.kind, value.key) }"
                :placeholder="isAmbiguous(group.kind, value.key) ? '同名成员，请选择' : ''"
                :aria-label="`${value.label}对应的${group.title}`"
                @update:model-value="choose(group.kind, value.key, $event)"
              >
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
                />
              </el-select>
            </li>
          </ul>
        </section>
      </template>

      <template v-else-if="step === 2">
        <yp-choice-cards
          v-model="duplicateMode"
          :options="DUPLICATE_MODE_OPTIONS"
          label="重复工作项的处理方式"
          :disabled="submitting"
        />
        <p class="wi-import__note">
          按标题匹配项目中未删除、未归档的工作项。
        </p>
        <inline-problem
          v-if="previewProblem"
          :problem="previewProblem"
        />
        <p
          v-else-if="previewing"
          class="wi-import__note"
          role="status"
        >
          正在预览导入结果…
        </p>
        <template v-else-if="preview">
          <dl
            class="wi-stats"
            aria-label="预览统计"
          >
            <div
              v-for="stat in previewStats"
              :key="stat.label"
              :class="{ 'is-failed': stat.failed }"
            >
              <dt>{{ stat.label }}</dt>
              <dd>{{ stat.value }}</dd>
            </div>
          </dl>
          <div v-if="previewIssues.length">
            <el-button
              text
              :aria-expanded="showIssues"
              @click="showIssues = !showIssues"
            >
              {{ showIssues ? '收起' : '查看' }}问题列表（{{ previewIssues.length }} 行）
            </el-button>
            <ul
              v-if="showIssues"
              class="wi-issues"
              aria-label="问题列表"
            >
              <li
                v-for="item in previewIssues"
                :key="item.rowNumber"
              >
                <span class="wi-issues__row">第 {{ item.rowNumber }} 行</span>
                <span class="wi-issues__title">{{ item.title }}</span>
                <span
                  class="wi-issues__tag"
                  :class="{ 'is-error': item.outcome === WorkItemImportOutcome.Error }"
                >{{ outcomeLabel(item.outcome) }}</span>
                <span class="wi-issues__text">{{ item.messages.join('；') }}</span>
              </li>
            </ul>
          </div>
        </template>
        <inline-problem
          v-if="submitProblem"
          :problem="submitProblem"
        />
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
:global(.work-item-import-dialog .el-dialog__body) { max-height: min(62vh, 560px); overflow-y: auto; }
:global(.work-item-import-dialog .el-dialog__footer) { display: flex; justify-content: flex-end; gap: var(--yp-space-2); padding-top: var(--yp-space-5); }

.wi-import__title {
  margin: 0 0 var(--yp-space-3);
  color: var(--yp-text-primary);
  font: 600 var(--yp-type-section-title-size) / var(--yp-type-section-title-line) var(--yp-font-heading);
}

.wi-import {
  display: grid;
  gap: var(--yp-space-4);
  font-size: var(--yp-type-body-size);
}

.wi-import__note,
.wi-import__loaded,
.wi-values__hint {
  margin: 0;
  color: var(--yp-text-secondary);
}

.wi-import__loaded { color: var(--yp-text-primary); }
.wi-import__error { margin: 0; color: var(--yp-status-red-foreground); }
.wi-import__warn { margin: 0; color: var(--yp-status-orange-foreground); }

.wi-map {
  display: grid;
  border: 1px solid var(--yp-border-default);
  border-radius: var(--yp-radius-md);
}

.wi-map__row {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1.4fr) minmax(0, 1fr);
  align-items: center;
  gap: var(--yp-space-3);
  padding: var(--yp-space-2) var(--yp-space-3);
  border-top: 1px solid var(--yp-border-default);
}

.wi-map__row:first-child { border-top: 0; }

.wi-map__row--head {
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
  font-size: 12px;
}

.wi-map__row--title { background: var(--yp-bg-hover); }
.wi-map__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.wi-map__sample {
  overflow: hidden;
  color: var(--yp-text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wi-map__target { font-weight: 600; }
.wi-map__target em { margin-left: var(--yp-space-2); color: var(--yp-text-secondary); font-size: 12px; font-style: normal; font-weight: 400; }

.wi-values { display: grid; gap: var(--yp-space-2); }
.wi-values h3 { margin: 0; font-size: 14px; font-weight: 600; }
.wi-values__list { display: grid; margin: 0; padding: 0; gap: var(--yp-space-2); list-style: none; }

.wi-values__item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: var(--yp-space-3);
}

.wi-values__label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wi-values__label small { margin-left: var(--yp-space-2); color: var(--yp-text-muted); }
.wi-values__item :deep(.is-unresolved .el-select__wrapper) { box-shadow: 0 0 0 1px var(--yp-status-red-foreground) inset; }

.wi-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  gap: var(--yp-space-3);
}

.wi-stats > div {
  padding: var(--yp-space-3) var(--yp-space-4);
  border: 1px solid var(--yp-border-default);
  border-radius: var(--yp-radius-md);
}

.wi-stats dt { color: var(--yp-text-secondary); font-size: 12px; }
.wi-stats dd { margin: 0; font-size: 20px; font-weight: 600; }
.wi-stats .is-failed dd { color: var(--yp-status-red-foreground); }

.wi-issues {
  display: grid;
  max-height: 220px;
  margin: var(--yp-space-2) 0 0;
  padding: 0;
  overflow-y: auto;
  border: 1px solid var(--yp-border-default);
  border-radius: var(--yp-radius-md);
  list-style: none;
}

.wi-issues li {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 64px minmax(0, 2fr);
  gap: var(--yp-space-3);
  padding: var(--yp-space-2) var(--yp-space-3);
  border-top: 1px solid var(--yp-border-default);
}

.wi-issues li:first-child { border-top: 0; }
.wi-issues__row { color: var(--yp-text-secondary); }
.wi-issues__title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wi-issues__tag { color: var(--yp-text-secondary); }
.wi-issues__tag.is-error { color: var(--yp-status-red-foreground); }
.wi-issues__text { color: var(--yp-text-secondary); }

@media (max-width: 640px) {
  .wi-map__row,
  .wi-values__item,
  .wi-issues li { grid-template-columns: minmax(0, 1fr); }
  .wi-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
