<script setup lang="ts">
import YpNotice from '../components/yp/YpNotice.vue'
import { usePageLoad } from '../brand/page-progress'

import { computed, onBeforeUnmount, ref, type DefineComponent } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElMessage, ElOption as ElOptionRaw, ElSelect as ElSelectRaw, ElTable, ElTableColumn } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import {
  MigratedTimeImportOutcome, readCsrfToken,
  type MigratedTimeImportRequest, type MigratedTimeImportResult, type TeamDashboardOptions,
} from '@yumpoo/api-client'
import { teamDashboardApi } from '../api/client'
import { problemMessage, toApiProblem } from '../api/problems'
import ImportFileDrop from '../components/import/ImportFileDrop.vue'
import ImportSteps from '../components/import/ImportSteps.vue'
import { readWorkbook } from '../components/import/spreadsheet'
import YpModulePage from '../components/yp/YpModulePage.vue'
import {
  candidatesFor, defaultChoice, describeIssue, duplicateTargets, hoursLabel, importRows, isInformational, MIGRATED_ISSUE_LABELS, parseHoursDetail,
  requestRow, type DetailMember, type MemberChoice, type ParsedDetail,
} from '../components/team/hoursImportModel'
import { fromApiDate } from '../components/team/teamDashboardModel'

const pageLoad = usePageLoad()
const ElSelect = ElSelectRaw as unknown as DefineComponent
const ElOption = ElOptionRaw as unknown as DefineComponent

const STEPS = ['上传文件', '匹配成员', '预览并迁入'] as const
const SKIP = '__skip__'
const SHOWN_ERRORS = 20

const router = useRouter()
const step = ref(0)

const options = ref<TeamDashboardOptions>(), optionsError = ref('')
const candidates = computed(() => (options.value?.members ?? []).filter(member => member.active)
  .map(member => ({ userId: member.userId, displayName: member.displayName.trim() })))
async function loadOptions(): Promise<void> {
  optionsError.value = ''
  try { options.value = await teamDashboardApi.getTeamDashboardOptions() } catch (reason) { optionsError.value = problemMessage(await toApiProblem(reason)) }
}
void pageLoad.track(loadOptions)

const fileName = ref(''), reading = ref(false), readError = ref('')
const detail = ref<ParsedDetail>()
const choices = ref<MemberChoice[]>([])
const members = computed<DetailMember[]>(() => detail.value?.members ?? [])
const dateRange = computed(() => {
  const dates = detail.value?.dates ?? []
  return dates.length ? `${dates[0]} 至 ${dates[dates.length - 1]}（${dates.length} 天）` : ''
})
const shownErrors = computed(() => (detail.value?.errors ?? []).slice(0, SHOWN_ERRORS))
const canMatch = computed(() => Boolean(detail.value) && !detail.value!.errors.length && Boolean(options.value))

async function onFile(file: File): Promise<void> {
  reading.value = true
  readError.value = ''
  detail.value = undefined
  choices.value = []
  fileName.value = file.name
  try {
    const parsed = parseHoursDetail(await readWorkbook(file))
    if (parsed.ok) detail.value = parsed.detail
    else readError.value = parsed.message
  } catch (reason) {
    readError.value = reason instanceof Error && reason.message ? reason.message : '无法读取该文件。'
  } finally { reading.value = false }
}

function toMatch(): void {
  if (choices.value.length !== members.value.length) choices.value = members.value.map(member => defaultChoice(member.name, candidates.value))
  step.value = 1
}

const labelOf = (userId: string, displayName: string): string =>
  candidates.value.filter(candidate => candidate.displayName === displayName).length > 1 ? `${displayName}（${userId.slice(-4)}）` : displayName
const selectable = computed(() => candidates.value.map(candidate => ({ userId: candidate.userId, label: labelOf(candidate.userId, candidate.displayName) }))
  .sort((a, b) => a.label.localeCompare(b.label, 'zh-CN')))
const selected = (index: number): string | undefined => choices.value[index] === null ? SKIP : choices.value[index]
function choose(index: number, value: string): void {
  const next = [...choices.value]
  next[index] = value === SKIP ? null : value
  choices.value = next
}
function memberHint(member: DetailMember, index: number): string {
  const choice = choices.value[index]
  if (choice === undefined) return `平台有 ${candidatesFor(member.name, candidates.value).length} 位同名成员，请选择`
  if (choice === null) return candidatesFor(member.name, candidates.value).length ? '已选择跳过' : '平台没有同名成员，将跳过'
  return ''
}
const repeated = computed(() => duplicateTargets(choices.value))
const unresolved = computed(() => choices.value.filter(choice => choice === undefined).length)
const targets = computed(() => detail.value ? importRows(detail.value, choices.value) : [])
const migrating = computed(() => members.value.filter((_, index) => choices.value[index]).length)
const matchProblem = computed(() => {
  if (unresolved.value) return `还有 ${unresolved.value} 位同名成员没有选择。`
  if (repeated.value.length) return '有多个姓名选择了同一位成员，请为每个姓名选择不同的成员。'
  if (!targets.value.length) return '没有可迁入的成员，请至少为一位成员选择平台成员。'
  return ''
})

const result = ref<MigratedTimeImportResult>()
const previewing = ref(false), previewError = ref(''), submitting = ref(false)
let previewToken = 0
let attempt: { body: string; key: string } | undefined
const request = (dryRun: boolean): MigratedTimeImportRequest => ({ dryRun, rows: targets.value.map(requestRow) })

async function toPreview(): Promise<void> {
  step.value = 2
  const token = ++previewToken
  result.value = undefined
  previewError.value = ''
  const csrf = readCsrfToken()
  if (!csrf) { previewError.value = '缺少 CSRF 凭据，请刷新后重试。'; return }
  previewing.value = true
  try {
    const preview = await teamDashboardApi.importMigratedTime({ xXSRFTOKEN: csrf, idempotencyKey: crypto.randomUUID(), migratedTimeImportRequest: request(true) })
    if (token === previewToken) result.value = preview
  } catch (reason) {
    if (token === previewToken) previewError.value = problemMessage(await toApiProblem(reason))
  } finally { if (token === previewToken) previewing.value = false }
}

function back(): void { ++previewToken; previewing.value = false; step.value -= 1 }

const names = computed(() => new Map(members.value.flatMap((member, index) => choices.value[index] ? [[choices.value[index]!, member.name] as const] : [])))
const replacedDays = computed(() => result.value?.days.filter(day => day.issues.some(issue => issue.code === 'REPLACES_PREVIOUS')).length ?? 0)
const issueRows = computed(() => (result.value?.days ?? []).flatMap(day => day.issues.filter(issue => !isInformational(issue)).map((issue, index) => ({
  key: `${day.userId}:${fromApiDate(day.workDate)}:${index}`, member: names.value.get(day.userId) ?? '未知成员', date: fromApiDate(day.workDate),
  level: day.outcome === MigratedTimeImportOutcome.Error ? '错误' : '警告', text: describeIssue(issue),
}))))
const skippedList = computed(() => (result.value?.skippedRows ?? []).map(skipped => {
  const row = targets.value[skipped.index]!
  return { key: skipped.index, member: row.name, date: row.date, project: row.projectName || '（空）', title: row.title,
    hours: hoursLabel(row.minutes), reason: MIGRATED_ISSUE_LABELS[skipped.code] ?? skipped.code }
}))
const writable = computed(() => result.value?.importedDayCount ?? 0)

async function submit(): Promise<void> {
  if (submitting.value || !result.value || !writable.value) return
  const csrf = readCsrfToken()
  if (!csrf) { previewError.value = '缺少 CSRF 凭据，请刷新后重试。'; return }
  const body = request(false)
  const serialized = JSON.stringify(body)
  if (attempt?.body !== serialized) attempt = { body: serialized, key: crypto.randomUUID() }
  submitting.value = true
  previewError.value = ''
  try {
    const done = await teamDashboardApi.importMigratedTime({ xXSRFTOKEN: csrf, idempotencyKey: attempt.key, migratedTimeImportRequest: body })
    const parts = [`已迁入 ${done.importedDayCount} 天、${hoursLabel(done.importedMinutes)} 小时`]
    if (done.skippedRows.length) parts.push(`${done.skippedRows.length} 条工时找不到工作项，未迁入`)
    if (done.failedDayCount) parts.push(`${done.failedDayCount} 天因问题未写入`)
    ElMessage.success(parts.join('，'))
    await router.push({ name: 'team', params: { view: 'timesheet' } })
  } catch (reason) { previewError.value = problemMessage(await toApiProblem(reason)) }
  finally { submitting.value = false }
}

onBeforeUnmount(() => { ++previewToken })
</script>

<template>
  <yp-module-page
    module="team"
    title="迁入工时"
  >
    <template #actions>
      <el-button
        :icon="ArrowLeft"
        text
        @click="router.push({ name: 'team', params: { view: 'timesheet' } })"
      >
        返回成员工时
      </el-button>
    </template>
    <div class="hours-import">
      <yp-notice class="hours-import__note">
        迁入工时用于导入原平台导出的「工时花费报表」：每位成员一个明细工作表，每行是成员某天在某个工作项上花费的工时，汇总表会被忽略。系统按「所属项目」和「标题」找到 Yumpoo 中同名的项目和工作项，把工时记到该工作项上；找不到的工时不会迁入，请先执行工作项导入。原平台只有每天的时长，系统会从成员的上班时间开始，按表格顺序把当天各工作项的工时依次排进空闲时段，所以排出的时段不代表实际工作时间。迁入的记录在个人工时日历、我的一天和团队工时表中标为「迁入工时」，成员不能修改。同一成员同一天再次迁入会覆盖上次的结果。
      </yp-notice>
      <import-steps
        :steps="STEPS"
        :current="step"
      />
      <yp-notice
        v-if="optionsError"
        tone="danger"
      >
        {{ `成员与项目列表加载失败。${optionsError}` }}

        <template #actions>
          <el-button
            text
            @click="loadOptions"
          >
            重试
          </el-button>
        </template>
      </yp-notice>

      <section
        v-if="step === 0"
        class="hours-import__panel"
        aria-label="上传文件"
      >
        <import-file-drop
          :disabled="reading"
          @file="onFile"
        />
        <p
          v-if="reading"
          class="hours-import__muted"
        >
          正在读取 {{ fileName }}…
        </p>
        <yp-notice
          v-if="readError"
          tone="danger"
        >
          {{ readError }}
        </yp-notice>
        <template v-if="detail">
          <dl class="hours-import__facts">
            <div><dt>文件</dt><dd>{{ fileName }}</dd></div>
            <div><dt>明细表</dt><dd>{{ detail.sheets.join('、') }}</dd></div>
            <div><dt>日期</dt><dd>{{ dateRange || '无' }}</dd></div>
            <div><dt>成员</dt><dd>{{ members.length }} 位</dd></div>
            <div><dt>工时记录</dt><dd>{{ detail.rows.length }} 条</dd></div>
            <div v-if="detail.ignoredSheets.length">
              <dt>已忽略的工作表</dt><dd>{{ detail.ignoredSheets.join('、') }}</dd>
            </div>
          </dl>
          <yp-notice
            v-if="detail.errors.length"
            tone="danger"
            :title="`发现 ${detail.errors.length} 个问题，请修正文件后重新上传`"
          >
            <ul class="hours-import__errors">
              <li
                v-for="(error, index) in shownErrors"
                :key="index"
              >
                {{ error }}
              </li>
            </ul>
            <p
              v-if="detail.errors.length > shownErrors.length"
              class="hours-import__muted"
            >
              另有 {{ detail.errors.length - shownErrors.length }} 个问题未列出。
            </p>
          </yp-notice>
        </template>
        <footer class="hours-import__footer">
          <el-button
            type="primary"
            :disabled="!canMatch"
            @click="toMatch"
          >
            下一步
          </el-button>
        </footer>
      </section>

      <section
        v-else-if="step === 1"
        class="hours-import__panel"
        aria-label="匹配成员"
      >
        <p class="hours-import__muted">
          按姓名匹配平台成员（在职且启用）。匹配不到的成员默认跳过，也可以手动指定；同名多人需要手动选择。
        </p>
        <el-table
          :data="members"
          border
          max-height="420"
          row-key="name"
        >
          <el-table-column
            label="表格姓名"
            prop="name"
            min-width="140"
          />
          <el-table-column
            label="合计小时"
            width="110"
            align="right"
          >
            <template #default="{ row }">
              {{ hoursLabel((row as DetailMember).totalMinutes) }}
            </template>
          </el-table-column>
          <el-table-column
            label="天数"
            width="80"
            align="right"
          >
            <template #default="{ row }">
              {{ (row as DetailMember).days }}
            </template>
          </el-table-column>
          <el-table-column
            label="平台成员"
            min-width="260"
          >
            <template #default="{ row, $index }">
              <el-select
                :model-value="selected($index)"
                filterable
                placeholder="请选择平台成员"
                :aria-label="`${(row as DetailMember).name} 对应的平台成员`"
                @update:model-value="(value: string) => choose($index, value)"
              >
                <el-option
                  :value="SKIP"
                  label="跳过（不迁入）"
                />
                <el-option
                  v-for="candidate in selectable"
                  :key="candidate.userId"
                  :value="candidate.userId"
                  :label="candidate.label"
                />
              </el-select>
              <span
                v-if="memberHint(row as DetailMember, $index)"
                class="hours-import__hint"
                :class="{ 'is-warning': choices[$index] === undefined }"
              >{{ memberHint(row as DetailMember, $index) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <yp-notice
          v-if="matchProblem"
          tone="warning"
        >
          {{ matchProblem }}
        </yp-notice>
        <footer class="hours-import__footer">
          <span class="hours-import__muted">{{ migrating }} / {{ members.length }} 位成员将迁入</span>
          <el-button @click="back">
            上一步
          </el-button>
          <el-button
            type="primary"
            :disabled="Boolean(matchProblem)"
            @click="toPreview"
          >
            下一步
          </el-button>
        </footer>
      </section>

      <section
        v-else
        v-loading="previewing"
        class="hours-import__panel"
        aria-label="预览并迁入"
      >
        <yp-notice
          v-if="previewError"
          tone="danger"
        >
          {{ previewError }}
        </yp-notice>
        <template v-if="result">
          <ul
            class="hours-import__stats"
            aria-label="预览统计"
          >
            <li><strong>{{ result.importedDayCount }}</strong><span>迁入天数</span></li>
            <li><strong>{{ hoursLabel(result.importedMinutes) }}</strong><span>合计小时</span></li>
            <li><strong>{{ replacedDays }}</strong><span>覆盖天数</span></li>
            <li><strong>{{ hoursLabel(result.skippedMinutes) }}</strong><span>未迁入小时</span></li>
            <li :class="{ 'is-problem': result.failedDayCount }">
              <strong>{{ result.failedDayCount }}</strong><span>问题天数</span>
            </li>
          </ul>
          <p
            v-if="result.replacedMinutes"
            class="hours-import__muted"
          >
            将覆盖原有迁入工时共 {{ hoursLabel(result.replacedMinutes) }} 小时。
          </p>
          <template v-if="skippedList.length">
            <yp-notice
              tone="warning"
              :title="`${skippedList.length} 条工时（${hoursLabel(result.skippedMinutes)} 小时）对应不到工作项，不会迁入`"
            >
              所属项目或工作项不存在时，请先执行工作项导入：在对应项目的工作项表格中点 <strong>新增工作项 ▾ → 导入工作项</strong>（项目不存在时先新建同名项目）；成员不在项目中时，请先把成员加入项目。处理后重新迁入，会覆盖这些日期上次迁入的结果，不会重复计入。
            </yp-notice>
            <el-table
              :data="skippedList"
              border
              max-height="320"
              row-key="key"
            >
              <el-table-column
                label="成员"
                prop="member"
                min-width="100"
              />
              <el-table-column
                label="日期"
                prop="date"
                width="110"
              />
              <el-table-column
                label="所属项目"
                prop="project"
                min-width="160"
              />
              <el-table-column
                label="标题"
                prop="title"
                min-width="220"
              />
              <el-table-column
                label="小时"
                prop="hours"
                width="70"
                align="right"
              />
              <el-table-column
                label="原因"
                prop="reason"
                min-width="160"
              />
            </el-table>
          </template>
          <template v-if="issueRows.length">
            <p class="hours-import__muted">
              有问题的日期不会写入；警告只是提醒，该日仍会迁入。
            </p>
            <el-table
              :data="issueRows"
              border
              max-height="320"
              row-key="key"
            >
              <el-table-column
                label="成员"
                prop="member"
                min-width="120"
              />
              <el-table-column
                label="日期"
                prop="date"
                width="120"
              />
              <el-table-column
                label="级别"
                prop="level"
                width="80"
              />
              <el-table-column
                label="说明"
                prop="text"
                min-width="220"
              />
            </el-table>
          </template>
        </template>
        <footer class="hours-import__footer">
          <el-button
            :disabled="submitting"
            @click="back"
          >
            上一步
          </el-button>
          <el-button
            type="primary"
            :loading="submitting"
            :disabled="!result || !writable || previewing"
            @click="submit"
          >
            迁入
          </el-button>
        </footer>
      </section>
    </div>
  </yp-module-page>
</template>

<style scoped>
.hours-import {
  display: grid;
  max-width: 960px;
  gap: var(--yp-space-4);
  margin: 0 auto;
}

.hours-import__note { margin: 0; }

.hours-import__panel {
  display: grid;
  gap: var(--yp-space-4);
}

.hours-import__muted {
  margin: 0;
  color: var(--yp-text-muted);
  font-size: 13px;
  line-height: 20px;
}

.hours-import__facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--yp-space-2) var(--yp-space-4);
  margin: 0;
  font-size: 13px;
}

.hours-import__facts div {
  display: flex;
  min-width: 0;
  gap: var(--yp-space-2);
}

.hours-import__facts dt {
  flex: none;
  color: var(--yp-text-muted);
}

.hours-import__facts dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.hours-import__errors {
  margin: var(--yp-space-2) 0 0;
  padding-left: var(--yp-space-4);
  font-size: 13px;
  line-height: 20px;
}

.hours-import__hint {
  display: block;
  margin-top: 2px;
  color: var(--yp-text-muted);
  font-size: 12px;
}

.hours-import__hint.is-warning {
  color: color-mix(in srgb, var(--yp-status-orange) 62%, var(--yp-text-primary));
}

.hours-import__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: var(--yp-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.hours-import__stats li {
  display: grid;
  gap: 2px;
  padding: var(--yp-space-3) var(--yp-space-4);
  border: 1px solid var(--yp-border-default);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}

.hours-import__stats strong {
  font: 700 22px / 1.2 var(--yp-font-mono);
  font-variant-numeric: tabular-nums;
}

.hours-import__stats span {
  color: var(--yp-text-secondary);
  font-size: 12px;
}

.hours-import__stats li.is-problem strong {
  color: var(--yp-status-red);
}

.hours-import__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--yp-space-2);
}

.hours-import__footer .hours-import__muted {
  margin-right: auto;
}
</style>
