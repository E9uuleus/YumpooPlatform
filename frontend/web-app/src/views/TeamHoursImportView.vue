<script setup lang="ts">
import { usePageLoad } from '../brand/page-progress'

import { computed, onBeforeUnmount, ref, type DefineComponent } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElMessage, ElOption as ElOptionRaw, ElSelect as ElSelectRaw, ElTable, ElTableColumn } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import {
  MigratedTimeImportOutcome, readCsrfToken, TeamDashboardProjectLifecycleEnum,
  type MigratedTimeImportRequest, type MigratedTimeImportResult, type TeamDashboardOptions,
} from '@yumpoo/api-client'
import { teamDashboardApi } from '../api/client'
import { problemMessage, toApiProblem } from '../api/problems'
import ImportFileDrop from '../components/import/ImportFileDrop.vue'
import ImportSteps from '../components/import/ImportSteps.vue'
import { readSpreadsheet } from '../components/import/spreadsheet'
import YpModulePage from '../components/yp/YpModulePage.vue'
import {
  buildEntries, candidatesFor, defaultChoice, describeIssue, duplicateTargets, hoursLabel, isInformational, parseHoursMatrix,
  type MatrixMember, type MemberChoice, type ParsedMatrix,
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
const projects = computed(() => (options.value?.projects ?? []).filter(project => project.lifecycle === TeamDashboardProjectLifecycleEnum.Active))
async function loadOptions(): Promise<void> {
  optionsError.value = ''
  try { options.value = await teamDashboardApi.getTeamDashboardOptions() } catch (reason) { optionsError.value = problemMessage(await toApiProblem(reason)) }
}
void pageLoad.track(loadOptions)

const fileName = ref(''), reading = ref(false), readError = ref('')
const matrix = ref<ParsedMatrix>()
const choices = ref<MemberChoice[]>([])
const projectId = ref('')
const members = computed<MatrixMember[]>(() => matrix.value?.members ?? [])
const dateRange = computed(() => {
  const dates = [...(matrix.value?.dates ?? [])].sort()
  return dates.length ? `${dates[0]} 至 ${dates[dates.length - 1]}（${dates.length} 天）` : ''
})
const shownErrors = computed(() => (matrix.value?.errors ?? []).slice(0, SHOWN_ERRORS))
const canMatch = computed(() => Boolean(matrix.value) && !matrix.value!.errors.length && Boolean(options.value))

async function onFile(file: File): Promise<void> {
  reading.value = true
  readError.value = ''
  matrix.value = undefined
  choices.value = []
  fileName.value = file.name
  try {
    const parsed = parseHoursMatrix(await readSpreadsheet(file))
    if (parsed.ok) matrix.value = parsed.matrix
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
function memberHint(member: MatrixMember, index: number): string {
  const choice = choices.value[index]
  if (choice === undefined) return `平台有 ${candidatesFor(member.name, candidates.value).length} 位同名成员，请选择`
  if (choice === null) return candidatesFor(member.name, candidates.value).length ? '已选择跳过' : '平台没有同名成员，将跳过'
  return ''
}
const repeated = computed(() => duplicateTargets(choices.value))
const unresolved = computed(() => choices.value.filter(choice => choice === undefined).length)
const targets = computed(() => buildEntries(members.value, choices.value))
const migrating = computed(() => members.value.filter((_, index) => choices.value[index]).length)
const matchProblem = computed(() => {
  if (unresolved.value) return `还有 ${unresolved.value} 位同名成员没有选择。`
  if (repeated.value.length) return '有多个姓名选择了同一位成员，同一成员同一天只能迁入一次。'
  if (!targets.value.length) return '没有可迁入的成员，请至少为一位成员选择平台成员。'
  return ''
})

const result = ref<MigratedTimeImportResult>()
const previewing = ref(false), previewError = ref(''), submitting = ref(false)
let previewToken = 0
let attempt: { body: string; key: string } | undefined
const request = (dryRun: boolean): MigratedTimeImportRequest => ({ dryRun, categoryProjectId: projectId.value || null, entries: targets.value })

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
const writable = computed(() => (result.value?.importedDayCount ?? 0) + (result.value?.clearedDayCount ?? 0))

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
    if (done.clearedDayCount) parts.push(`清除 ${done.clearedDayCount} 天`)
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
      <p class="hours-import__note">
        迁入工时用于导入原平台的历史工时，表格的行为成员、列为日期，单元格为当天的小时数。原平台只有每天的时长，系统会从成员的上班时间开始，依次排进当天的空闲时段，所以排出的时段不代表实际工作时间。迁入的记录在个人工时日历、我的一天和团队工时表中标为「迁入工时」，成员不能修改。同一成员同一天再次迁入会覆盖上次的结果，单元格填 0 可以清除。
      </p>
      <import-steps
        :steps="STEPS"
        :current="step"
      />
      <el-alert
        v-if="optionsError"
        type="error"
        :title="`成员与项目列表加载失败。${optionsError}`"
        :closable="false"
      >
        <el-button
          text
          @click="loadOptions"
        >
          重试
        </el-button>
      </el-alert>

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
        <el-alert
          v-if="readError"
          type="error"
          :title="readError"
          :closable="false"
        />
        <template v-if="matrix">
          <dl class="hours-import__facts">
            <div><dt>文件</dt><dd>{{ fileName }}</dd></div>
            <div><dt>表头行</dt><dd>第 {{ matrix.headerRow }} 行</dd></div>
            <div><dt>日期列</dt><dd>{{ dateRange || '无' }}</dd></div>
            <div><dt>成员</dt><dd>{{ members.length }} 位</dd></div>
            <div v-if="matrix.ignoredColumns.length">
              <dt>已忽略的列</dt><dd>{{ matrix.ignoredColumns.join('、') }}</dd>
            </div>
            <div v-if="matrix.ignoredRows.length">
              <dt>已忽略的行</dt><dd>{{ matrix.ignoredRows.join('、') }}</dd>
            </div>
          </dl>
          <el-alert
            v-if="matrix.errors.length"
            type="error"
            :closable="false"
            :title="`发现 ${matrix.errors.length} 个问题，请修正文件后重新上传`"
          >
            <ul class="hours-import__errors">
              <li
                v-for="(error, index) in shownErrors"
                :key="index"
              >
                {{ error.message }}
              </li>
            </ul>
            <p
              v-if="matrix.errors.length > shownErrors.length"
              class="hours-import__muted"
            >
              另有 {{ matrix.errors.length - shownErrors.length }} 个问题未列出。
            </p>
          </el-alert>
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
          row-key="rowNumber"
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
              {{ hoursLabel((row as MatrixMember).totalMinutes) }}
            </template>
          </el-table-column>
          <el-table-column
            label="天数"
            width="80"
            align="right"
          >
            <template #default="{ row }">
              {{ (row as MatrixMember).days.length }}
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
                :aria-label="`${(row as MatrixMember).name} 对应的平台成员`"
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
                v-if="memberHint(row as MatrixMember, $index)"
                class="hours-import__hint"
                :class="{ 'is-warning': choices[$index] === undefined }"
              >{{ memberHint(row as MatrixMember, $index) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="hours-import__project">
          <label id="hours-import-project">关联项目（可选）</label>
          <el-select
            v-model="projectId"
            clearable
            filterable
            placeholder="不关联项目"
            aria-labelledby="hours-import-project"
          >
            <el-option
              v-for="project in projects"
              :key="project.id"
              :value="project.id"
              :label="`${project.name}（${project.code}）`"
            />
          </el-select>
          <span class="hours-import__muted">选择后，这批迁入工时统一计入该项目的团队项目工时。</span>
        </div>
        <el-alert
          v-if="matchProblem"
          type="warning"
          :title="matchProblem"
          :closable="false"
        />
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
        <el-alert
          v-if="previewError"
          type="error"
          :title="previewError"
          :closable="false"
        />
        <template v-if="result">
          <ul
            class="hours-import__stats"
            aria-label="预览统计"
          >
            <li><strong>{{ result.importedDayCount }}</strong><span>迁入天数</span></li>
            <li><strong>{{ hoursLabel(result.importedMinutes) }}</strong><span>合计小时</span></li>
            <li><strong>{{ replacedDays }}</strong><span>覆盖天数</span></li>
            <li><strong>{{ result.clearedDayCount }}</strong><span>清除天数</span></li>
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

.hours-import__note {
  margin: 0;
  padding: var(--yp-space-3) var(--yp-space-4);
  border-left: 3px solid var(--yp-action-primary);
  border-radius: var(--yp-radius-sm);
  background: var(--yp-bg-sunken);
  color: var(--yp-text-secondary);
  font-size: 13px;
  line-height: 22px;
}

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
  color: var(--yp-status-red-foreground);
}

.hours-import__project {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-3);
  font-size: 13px;
}

.hours-import__project .el-select {
  width: 280px;
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
  color: var(--yp-status-red-foreground);
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
