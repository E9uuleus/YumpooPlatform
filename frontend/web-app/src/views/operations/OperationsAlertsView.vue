<script setup lang="ts">
import { ElOption, ElTabs } from '../../components/operations/elementPlus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElInput,
  ElInputNumber,
  ElPagination,
  ElSelect,
  ElSkeleton,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElMessage,
} from 'element-plus'
import {
  readCsrfToken,
  ListOperationsAlertsStatusEnum,
  GetOperationsMetricsRangeEnum,
  type OperationsAlert,
  type OperationsRule,
} from '@yumpoo/api-client'
import { identityAdministrationApi, operationsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
import {
  RULE_DESCRIPTIONS,
  duration,
  eventNames,
  metric,
  ruleNames,
  time,
  postureNames,
} from '../../components/operations/operationsPresentation'
import { useOperations } from '../../components/operations/operationsContext'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import InlineProblem from '../../components/InlineProblem.vue'
import OperationsChart from '../../components/operations/OperationsChart.vue'
const route = useRoute(),
  tab = ref('active'),
  page = ref(1),
  actionError = ref<ApiProblem>(),
  saving = ref(''),
  confirming = ref(false)
const overview = useOperations()
function subject(alert: OperationsAlert) {
  return alert.subjectKey === 'instance'
    ? '当前实例'
    : alert.ruleCode === 'DISK_LOW'
      ? (overview.data.value?.runtime.host.volumes.find((v) => v.id === alert.subjectKey)?.label ??
        '磁盘卷')
      : (postureNames[alert.subjectKey] ?? alert.subjectKey)
}
const query = useOperationsQuery(
  (signal) =>
    operationsApi.listOperationsAlerts(
      {
        status:
          tab.value === 'history'
            ? ListOperationsAlertsStatusEnum.Resolved
            : ListOperationsAlertsStatusEnum.Firing,
        page: page.value - 1,
        size: 20,
      },
      { signal },
    ),
  { interval: () => (tab.value === 'active' ? 15000 : 0) },
)
const ruleQuery = useOperationsQuery((signal) => operationsApi.listOperationsAlertRules({ signal }))
const rules = ref<OperationsRule[]>([]),
  baselines = ref(new Map<string, string>())
const signature = (rule: OperationsRule) =>
  JSON.stringify([rule.enabled, rule.warningThreshold, rule.criticalThreshold, rule.forSeconds])
watch(ruleQuery.data, (value) => {
  if (value) {
    rules.value = value.map((r) => ({ ...r }))
    value.forEach((r) => baselines.value.set(r.code, signature(r)))
  }
})
watch(tab, () => {
  page.value = 1
  if (tab.value !== 'rules') void query.refresh()
})
watch(page, () => void query.refresh())
const detailId = ref(typeof route.query.alert === 'string' ? route.query.alert : ''),
  detailOpen = ref(Boolean(detailId.value)),
  ack = ref<OperationsAlert>(),
  note = ref('')
const detail = useOperationsQuery(
  (signal) =>
    operationsApi.getOperationsAlert(
      { alertId: detailId.value },
      { signal },
    ),
  { enabled: () => detailOpen.value && !!detailId.value },
)
function open(alert: OperationsAlert) {
  openDetail(alert.id)
}
function openDetail(alertId: string) {
  const wasOpen = detailOpen.value
  detail.cancel()
  detail.data.value = undefined
  detail.error.value = undefined
  detailId.value = alertId
  detailOpen.value = true
  if (wasOpen) void detail.refresh()
}
watch(() => route.query.alert, (id) => {
  if (typeof id === 'string') openDetail(id)
  else detailOpen.value = false
})
let disposed = false
const mutationController = new AbortController()
onBeforeUnmount(() => {
  disposed = true
  mutationController.abort()
})
// 告警事件只携带操作人 ID；按需补齐姓名，查询失败记为空串并保留 ID 兜底，避免重复请求。
const actorNames = ref(new Map<string, string>())
const resolvingActors = new Set<string>()
watch(
  () => detail.data.value?.events,
  (events) => {
    const ids = new Set((events ?? []).map((event) => event.actorUserId).filter((id) => id != null))
    for (const userId of ids) {
      if (actorNames.value.has(userId) || resolvingActors.has(userId)) continue
      resolvingActors.add(userId)
      identityAdministrationApi
        .getMember({ userId }, { signal: mutationController.signal })
        .then((member) => member.displayName)
        .catch(() => '')
        .then((name) => {
          resolvingActors.delete(userId)
          if (!disposed) actorNames.value = new Map(actorNames.value).set(userId, name)
        })
    }
  },
)
async function acknowledge() {
  if (!ack.value) return
  const csrf = readCsrfToken()
  if (!csrf) {
    actionError.value = localProblem('缺少 CSRF 凭据，请刷新页面后重试。')
    return
  }
  confirming.value = true
  actionError.value = undefined
  try {
    await operationsApi.acknowledgeOperationsAlert(
      { alertId: ack.value.id, xXSRFTOKEN: csrf, operationsAcknowledge: { note: note.value } },
      { signal: mutationController.signal },
    )
    if (disposed) return
    ack.value = undefined
    note.value = ''
    void query.refresh()
    if (detailOpen.value) void detail.refresh()
    window.dispatchEvent(new Event('yumpoo-operations-alerts-changed'))
  } catch (reason) {
    if (!disposed) actionError.value = await toApiProblem(reason)
  } finally {
    if (!disposed) confirming.value = false
  }
}
async function save(rule: OperationsRule) {
  const csrf = readCsrfToken()
  if (!csrf) {
    actionError.value = localProblem('缺少 CSRF 凭据，请刷新页面后重试。')
    return
  }
  saving.value = rule.code
  actionError.value = undefined
  try {
    const result = await operationsApi.updateOperationsAlertRule(
      {
        code: rule.code,
        ifMatch: rule.etag,
        xXSRFTOKEN: csrf,
        operationsRuleUpdate: {
          enabled: rule.enabled,
          warningThreshold: rule.warningThreshold,
          criticalThreshold: rule.criticalThreshold,
          forSeconds: rule.forSeconds,
        },
      },
      { signal: mutationController.signal },
    )
    if (disposed) return
    Object.assign(rule, result)
    baselines.value.set(rule.code, signature(result))
    ElMessage.success('规则已保存')
    void query.refresh()
    window.dispatchEvent(new Event('yumpoo-operations-alerts-changed'))
  } catch (reason) {
    if (disposed) return
    const problem = await toApiProblem(reason)
    if (problem.kind === 'response' && (problem.status === 412 || problem.status === 409)) {
      actionError.value = localProblem('规则已被其他管理员修改，已刷新该行。请检查后重新编辑。')
      try {
        const latest = (
          await operationsApi.listOperationsAlertRules({ signal: mutationController.signal })
        ).find((r) => r.code === rule.code)
        if (latest && !disposed) {
          Object.assign(rule, latest)
          baselines.value.set(rule.code, signature(latest))
        }
      } catch (refreshError) {
        if (!disposed) actionError.value = await toApiProblem(refreshError)
      }
    } else actionError.value = problem
  } finally {
    if (!disposed) saving.value = ''
  }
}
function threshold(
  rule: OperationsRule,
  key: 'warningThreshold' | 'criticalThreshold',
  value: number | undefined,
) {
  if (value != null) rule[key] = rule.unit === 'RATIO' ? value / 100 : value
}
const active = computed(() => query.data.value?.items ?? [])
const trends = useOperationsQuery(
  (signal) =>
    operationsApi.getOperationsMetrics({ range: GetOperationsMetricsRangeEnum._1h }, { signal }),
  { enabled: () => tab.value === 'active' && active.value.length > 0, interval: () => 60000 },
)
function alertThreshold(alert: OperationsAlert): number | undefined {
  const value =
    alert.params[alert.severity === 'CRITICAL' ? 'criticalThreshold' : 'warningThreshold']
  return typeof value === 'number' ? value : undefined
}
function alertSeries(alert: OperationsAlert) {
  if (alert.ruleCode === 'DISK_LOW') {
    const free = trends.data.value?.series.find((s) => s.key === `disk.${alert.subjectKey}.free`),
      total = trends.data.value?.series.find((s) => s.key === `disk.${alert.subjectKey}.total`)
    return [
      {
        key: 'disk.freeRatio',
        unit: 'RATIO',
        values: (free?.values ?? []).map((value, i) =>
          value == null || !total?.values[i] ? null : value / total.values[i]!,
        ),
      },
    ]
  }
  return trends.data.value?.series.filter((s) => s.key === alert.params.metric) ?? []
}
const activeTabLabel = computed(() =>
  tab.value === 'active' && query.data.value
    ? `活跃告警（${query.data.value.totalElements}）`
    : '活跃告警',
)
const severityTone = (severity: string) => (severity === 'CRITICAL' ? 'red' : 'yellow')
const eventTones: Record<string, string> = {
  FIRED: 'red',
  ESCALATED: 'red',
  DEESCALATED: 'yellow',
  ACKNOWLEDGED: 'blue',
  RESOLVED: 'green',
}
const unitSuffix = (unit: string) =>
  unit === 'RATIO' ? '%' : unit === 'MS' ? '毫秒' : unit === 'SECONDS' ? '秒' : '次'
function logLink(alert: OperationsAlert) {
  return {
    name: 'operations-logs',
    query: {
      from: new Date(alert.startedAt.getTime() - 300000).toISOString(),
      to: new Date(alert.startedAt.getTime() + 300000).toISOString(),
    },
  }
}
</script>
<template>
  <inline-problem
    v-if="actionError"
    :problem="actionError"
  />
  <el-tabs v-model="tab">
    <el-tab-pane
      :label="activeTabLabel"
      name="active"
    /><el-tab-pane
      label="历史"
      name="history"
    /><el-tab-pane
      label="规则"
      name="rules"
    />
  </el-tabs>
  <inline-problem
    v-if="tab !== 'rules' && query.error.value"
    :problem="query.error.value"
  />
  <template v-if="tab === 'active'">
    <article
      v-for="alert in active"
      :key="alert.id"
      class="alert-card"
      :class="`ops-tone-${severityTone(alert.severity)}`"
    >
      <span
        class="alert-card__bar"
        aria-hidden="true"
      />
      <div class="alert-card__main">
        <div class="alert-card__title">
          <yp-status-tag
            domain="operations"
            :status="alert.severity"
            effect="soft"
            size="small"
          />
          <h3>{{ ruleNames[alert.ruleCode] ?? alert.ruleCode }} · {{ subject(alert) }}</h3>
        </div>
        <div class="alert-card__facts">
          <span>当前 <b>{{ metric(alert.lastValue, String(alert.params.unit)) }}</b></span>
          <span>阈值 <b>{{ metric(alertThreshold(alert), String(alert.params.unit)) }}</b></span>
          <span>极值 <b>{{ metric(alert.peakValue, String(alert.params.unit)) }}</b></span>
          <span>开始于 <b>{{ time(alert.startedAt) }}</b></span>
          <span>已持续 <b>{{ duration(Date.now() - alert.startedAt.getTime()) }}</b></span>
        </div>
        <operations-chart
          v-if="alertSeries(alert).length"
          :title="ruleNames[alert.ruleCode] + '最近一小时趋势'"
          :times="trends.data.value?.timestamps ?? []"
          :series="alertSeries(alert)"
          :threshold="alertThreshold(alert)"
          compact
        />
      </div>
      <div class="alert-card__side">
        <p
          v-if="alert.acknowledgedAt"
          class="alert-card__ack"
          :title="alert.acknowledgedByUserId ?? undefined"
        >
          已确认 · {{ time(alert.acknowledgedAt) }}
        </p>
        <span
          v-else
          class="ops-chip ops-tone-yellow alert-card__pending"
        >未确认</span>
        <el-button
          v-if="!alert.acknowledgedAt"
          type="primary"
          @click="
            ack = alert;
            note = ''
          "
        >
          确认
        </el-button>
        <el-button @click="open(alert)">
          详情与时间线
        </el-button>
        <div class="alert-card__links">
          <router-link
            class="ops-link"
            :to="{ name: 'operations-host' }"
          >
            查看指标
          </router-link>
          <router-link
            class="ops-link"
            :to="logLink(alert)"
          >
            查看日志
          </router-link>
        </div>
      </div>
    </article>
    <section
      v-if="!active.length && !query.loading.value"
      class="ops-card"
    >
      <yp-empty-state
        title="没有活跃告警"
        description="内置规则每 15 秒评估一次，恢复需连续正常 1 分钟。"
      />
    </section>
  </template>
  <section
    v-if="tab === 'history'"
    class="ops-card flush"
  >
    <el-table
      :data="active"
      empty-text="暂无告警历史"
      @row-click="open"
    >
      <el-table-column
        label="级别"
        width="100"
      >
        <template #default="{ row }">
          <yp-status-tag
            domain="operations"
            :status="row.severity"
            effect="soft"
            size="small"
          />
        </template>
      </el-table-column><el-table-column
        label="规则"
        min-width="160"
      >
        <template #default="{ row }">
          {{ ruleNames[row.ruleCode] ?? row.ruleCode }}
        </template>
      </el-table-column>
      <el-table-column label="对象">
        <template #default="{ row }">
          {{ subject(row as OperationsAlert) }}
        </template>
      </el-table-column>
      <el-table-column
        label="开始"
        min-width="160"
      >
        <template #default="{ row }">
          {{ time(row.startedAt) }}
        </template>
      </el-table-column><el-table-column label="持续">
        <template #default="{ row }">
          {{ duration((row.resolvedAt?.getTime() ?? Date.now()) - row.startedAt.getTime()) }}
        </template>
      </el-table-column><el-table-column label="极值">
        <template #default="{ row }">
          {{ metric(row.peakValue, String(row.params.unit)) }}
        </template>
      </el-table-column><el-table-column label="结束方式">
        <template #default="{ row }">
          {{ row.resolution === 'RULE_DISABLED' ? '规则停用' : '已恢复' }}
        </template>
      </el-table-column>
    </el-table>
  </section>
  <el-pagination
    v-if="tab !== 'rules' && (query.data.value?.totalElements ?? 0) > 20"
    v-model:current-page="page"
    class="ops-pagination"
    :page-size="20"
    :total="query.data.value?.totalElements ?? 0"
    layout="total, prev, pager, next"
  />
  <template v-if="tab === 'rules'">
    <inline-problem
      v-if="ruleQuery.error.value"
      :problem="ruleQuery.error.value"
    />
    <p class="ops-muted rules-hint">
      比例使用百分比；磁盘以剩余比例低于阈值触发，其他规则以达到阈值触发。恢复需连续正常 1 分钟。每次保存都会写入安全审计。
    </p>
    <section class="ops-card flush">
      <el-table
        :data="rules"
        row-key="code"
      >
        <el-table-column
          label="启用"
          width="80"
        >
          <template #default="{ row }">
            <el-switch
              v-model="row.enabled"
              :aria-label="'启用 ' + (ruleNames[row.code] ?? row.code)"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="规则"
          min-width="220"
        >
          <template #default="{ row }">
            <div class="rule-name">
              <b>{{ ruleNames[row.code] ?? row.code }}</b>
              <small>{{ RULE_DESCRIPTIONS[row.code] ?? '' }} · {{ row.comparison === 'BELOW' ? '低于' : '达到' }}阈值触发</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="警告阈值"
          width="200"
        >
          <template #default="{ row }">
            <span
              v-if="row.warningThreshold != null"
              class="rule-input"
            >
              <el-input-number
                :model-value="row.warningThreshold * (row.unit === 'RATIO' ? 100 : 1)"
                :precision="row.unit === 'RATIO' ? 2 : 0"
                :min="row.unit === 'RATIO' ? 0.01 : 1"
                :max="row.unit === 'RATIO' ? 100 : 1000000000"
                controls-position="right"
                :aria-label="'警告阈值 ' + ruleNames[row.code]"
                @update:model-value="threshold(row as OperationsRule, 'warningThreshold', $event)"
              />
              <span class="ops-muted">{{ unitSuffix(row.unit) }}</span>
            </span><span
              v-else
              class="ops-muted"
            >—</span>
          </template>
        </el-table-column>
        <el-table-column
          label="严重阈值"
          width="200"
        >
          <template #default="{ row }">
            <span class="rule-input">
              <el-input-number
                :model-value="row.criticalThreshold * (row.unit === 'RATIO' ? 100 : 1)"
                :precision="row.unit === 'RATIO' ? 2 : 0"
                :min="row.unit === 'RATIO' ? 0.01 : 1"
                :max="row.unit === 'RATIO' ? 100 : 1000000000"
                controls-position="right"
                :aria-label="'严重阈值 ' + ruleNames[row.code]"
                @update:model-value="threshold(row as OperationsRule, 'criticalThreshold', $event)"
              />
              <span class="ops-muted">{{ unitSuffix(row.unit) }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column
          label="持续时间"
          width="145"
        >
          <template #default="{ row }">
            <el-select
              v-model="row.forSeconds"
              :disabled="['DB_UNAVAILABLE', 'CONFIG_POSTURE'].includes(row.code)"
              :aria-label="'持续时间 ' + ruleNames[row.code]"
            >
              <el-option
                v-for="seconds in [0, 30, 60, 120, 300, 600, 900]"
                :key="seconds"
                :value="seconds"
                :label="
                  seconds === 0 ? '立即' : seconds < 60 ? seconds + ' 秒' : seconds / 60 + ' 分钟'
                "
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="90"
        >
          <template #default="{ row }">
            <el-button
              size="small"
              :type="baselines.get(row.code) === signature(row as OperationsRule) ? 'default' : 'primary'"
              :loading="saving === row.code"
              :disabled="!!saving || baselines.get(row.code) === signature(row as OperationsRule)"
              @click="save(row as OperationsRule)"
            >
              保存
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </template>
  <el-dialog
    :model-value="!!ack"
    class="operations-overlay"
    title="确认告警"
    width="min(480px, 90vw)"
    @close="ack = undefined"
  >
    <p>{{ ruleNames[ack?.ruleCode ?? ''] }}。确认后告警继续跟踪，指标恢复后自动结束。</p>
    <el-input
      v-model="note"
      type="textarea"
      :maxlength="200"
      show-word-limit
      placeholder="处理备注（可选）"
      aria-label="确认备注"
    />
    <template #footer>
      <el-button @click="ack = undefined">
        取消
      </el-button><el-button
        type="primary"
        :loading="confirming"
        @click="acknowledge"
      >
        确认
      </el-button>
    </template>
  </el-dialog>
  <el-dialog
    v-model="detailOpen"
    class="operations-overlay alert-detail-dialog"
    title="告警详情"
    width="min(960px, calc(100vw - 48px))"
    align-center
    append-to-body
  >
    <template #header="{ titleId }">
      <div class="alert-detail__heading">
        <span class="ops-muted">告警详情与时间线</span>
        <h2 :id="titleId">
          {{ detail.data.value
            ? (ruleNames[detail.data.value.alert.ruleCode] ?? detail.data.value.alert.ruleCode)
            : '告警详情' }}
        </h2>
        <div
          v-if="detail.data.value"
          class="alert-detail__head"
        >
          <yp-status-tag
            domain="operations"
            :status="detail.data.value.alert.severity"
            effect="soft"
            size="small"
          />
          <yp-status-tag
            domain="operations"
            :status="detail.data.value.alert.status"
            effect="soft"
            size="small"
          />
          <span class="ops-muted">{{ subject(detail.data.value.alert) }}</span>
        </div>
      </div>
    </template>
    <div
      v-if="detail.loading.value && !detail.data.value"
      class="alert-detail__loading"
      role="status"
    >
      <p class="ops-muted">
        正在加载告警详情…
      </p>
      <el-skeleton
        :rows="5"
        animated
      />
    </div>
    <div
      v-if="detail.error.value"
      class="alert-detail__error"
    >
      <inline-problem :problem="detail.error.value" />
      <el-button @click="detail.refresh">
        重新加载
      </el-button>
    </div>
    <div
      v-if="detail.data.value"
      class="alert-detail__content"
    >
      <section class="alert-detail__summary">
        <h3>指标与状态</h3>
        <dl class="alert-detail__stats">
          <div><dt>当前值</dt><dd>{{ metric(detail.data.value.alert.lastValue, String(detail.data.value.alert.params.unit)) }}</dd></div>
          <div><dt>触发阈值</dt><dd>{{ metric(alertThreshold(detail.data.value.alert), String(detail.data.value.alert.params.unit)) }}</dd></div>
          <div><dt>极值</dt><dd>{{ metric(detail.data.value.alert.peakValue, String(detail.data.value.alert.params.unit)) }}</dd></div>
          <div><dt>持续时间</dt><dd>{{ duration((detail.data.value.alert.resolvedAt?.getTime() ?? Date.now()) - detail.data.value.alert.startedAt.getTime()) }}</dd></div>
        </dl>
        <dl class="ops-definition alert-detail__info">
          <dt>首次触发</dt><dd>{{ time(detail.data.value.alert.startedAt) }}</dd>
          <dt>最近评估</dt><dd>{{ time(detail.data.value.alert.evaluatedAt) }}</dd>
          <dt>确认状态</dt><dd>{{ detail.data.value.alert.acknowledgedAt ? '已确认' : '未确认' }}</dd>
          <template v-if="detail.data.value.alert.acknowledgedAt">
            <dt>确认时间</dt><dd>{{ time(detail.data.value.alert.acknowledgedAt) }}</dd>
          </template>
          <template v-if="detail.data.value.alert.resolvedAt">
            <dt>结束时间</dt><dd>{{ time(detail.data.value.alert.resolvedAt) }}</dd>
            <dt>结束方式</dt><dd>{{ detail.data.value.alert.resolution === 'RULE_DISABLED' ? '规则停用' : '已恢复' }}</dd>
          </template>
        </dl>
        <template v-if="detail.data.value.alert.acknowledgeNote">
          <h3>处理备注</h3>
          <p class="ops-callout alert-detail__note">
            {{ detail.data.value.alert.acknowledgeNote }}
          </p>
        </template>
      </section>
      <section class="alert-detail__timeline">
        <div class="alert-detail__timeline-head">
          <h3>事件时间线</h3>
          <span class="ops-muted">{{ detail.data.value.events.length }} 条事件</span>
        </div>
        <yp-empty-state
          v-if="!detail.data.value.events.length"
          title="暂无事件"
        />
        <ul
          v-else
          class="ops-timeline"
        >
          <li
            v-for="item in detail.data.value.events"
            :key="item.id"
          >
            <span class="ops-timeline__when">{{ item.occurredAt.toLocaleTimeString('zh-CN', { hour12: false }) }}</span>
            <span
              class="ops-timeline__rail"
              :class="`ops-tone-${eventTones[item.eventType] ?? 'gray'}`"
            ><span class="ops-timeline__node" /></span>
            <div class="ops-timeline__what">
              <span><b>{{ eventNames[item.eventType] ?? item.eventType }}</b> ·
                {{ metric(item.value, String(detail.data.value.alert.params.unit)) }}</span>
              <small>{{ time(item.occurredAt) }}</small>
              <div
                v-if="item.actorUserId"
                class="alert-actor"
              >
                <yp-assignee
                  v-if="actorNames.get(item.actorUserId)"
                  :user-id="item.actorUserId"
                  :display-name="actorNames.get(item.actorUserId)"
                  size="table"
                />
                <span
                  v-else
                  class="ops-muted"
                >操作人</span>
                <small :title="item.actorUserId">{{ item.actorUserId }}</small>
              </div>
            </div>
          </li>
        </ul>
      </section>
    </div>
    <template #footer>
      <div class="alert-detail__footer">
        <div
          v-if="detail.data.value"
          class="alert-card__links"
        >
          <router-link
            class="ops-link"
            :to="{ name: 'operations-host' }"
          >
            查看指标
          </router-link>
          <router-link
            class="ops-link"
            :to="logLink(detail.data.value.alert)"
          >
            查看日志
          </router-link>
        </div>
        <el-button @click="detailOpen = false">
          关闭
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.alert-card {
  display: grid;
  grid-template-columns: 4px minmax(0, 1fr) 220px;
  gap: 0 var(--yp-space-4);
  margin-bottom: var(--yp-space-3);
  overflow: hidden;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}

.alert-card__bar {
  background: var(--ops-tone);
}

.alert-card__main {
  display: grid;
  gap: var(--yp-space-2);
  min-width: 0;
  padding: var(--yp-space-4) 0;
}

.alert-card__title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-2);
}

.alert-card__title h3 {
  margin: 0;
  font: 500 15px / 22px var(--yp-font-heading);
}

.alert-card__facts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--yp-space-1) var(--yp-space-5);
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
}

.alert-card__facts b {
  color: var(--yp-text-primary);
  font-variant-numeric: tabular-nums;
}

.alert-card__side {
  display: grid;
  align-content: start;
  gap: var(--yp-space-2);
  padding: var(--yp-space-4) var(--yp-space-4) var(--yp-space-4) 0;
}

.alert-card__side .el-button + .el-button {
  margin-left: 0;
}

.alert-card__ack {
  margin: 0;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
}

.alert-card__pending {
  justify-self: start;
}

.alert-card__links {
  display: flex;
  gap: var(--yp-space-3);
}

.rules-hint {
  margin: 0 0 var(--yp-space-3);
}

.rule-name {
  display: grid;
  gap: 2px;
}

.rule-name small {
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}

.rule-input {
  display: inline-flex;
  align-items: center;
  gap: var(--yp-space-2);
}

.el-input-number {
  width: 130px;
}

.alert-actor {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-2);
}

.alert-detail__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-2);
}

.alert-detail__heading {
  display: grid;
  gap: var(--yp-space-2);
  padding-right: var(--yp-space-5);
}

.alert-detail__heading h2 {
  margin: 0;
  font: 500 var(--yp-type-section-title-size) / 1.4 var(--yp-font-heading);
}

:global(.alert-detail-dialog.el-dialog) {
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 48px);
  overflow: hidden;
}

:global(.alert-detail-dialog .el-dialog__header) {
  flex: none;
  padding-bottom: var(--yp-space-4);
  border-bottom: 1px solid var(--yp-border-subtle);
}

:global(.alert-detail-dialog .el-dialog__body) {
  min-height: 0;
  padding-block: var(--yp-space-5);
  overflow-y: auto;
}

:global(.alert-detail-dialog .el-dialog__footer) {
  flex: none;
  padding-top: var(--yp-space-4);
  border-top: 1px solid var(--yp-border-subtle);
}

.alert-detail__content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
  gap: var(--yp-space-6);
}

.alert-detail__content h3 {
  margin: 0 0 var(--yp-space-3);
  color: var(--yp-text-primary);
  font: 500 var(--yp-type-card-title-size) / 1.5 var(--yp-font-heading);
}

.alert-detail__stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-border-subtle);
}

.alert-detail__stats > div {
  padding: var(--yp-space-3);
  background: var(--yp-bg-sunken);
}

.alert-detail__stats dt {
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}

.alert-detail__stats dd {
  margin: var(--yp-space-1) 0 0;
  color: var(--yp-text-primary);
  font: 500 20px / 1.5 var(--yp-font-heading);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.alert-detail__info {
  margin: var(--yp-space-5) 0;
  font-size: var(--yp-type-caption-size);
}

.alert-detail__note {
  margin: 0;
  overflow-wrap: anywhere;
}

.alert-detail__timeline-head,
.alert-detail__footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--yp-space-3);
}

.alert-detail__footer .el-button {
  margin-left: auto;
}

.alert-detail__error {
  display: grid;
  justify-items: start;
  gap: var(--yp-space-3);
}

@media (max-width: 760px) {
  .alert-detail__content {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--yp-space-4);
  }

  .alert-detail__stats dd {
    font-size: clamp(16px, 4vw, 20px);
  }
}

@media (max-width: 960px) {
  .alert-card {
    grid-template-columns: 4px minmax(0, 1fr);
  }

  .alert-card__side {
    grid-column: 2;
    padding: 0 var(--yp-space-4) var(--yp-space-4) 0;
  }
}
</style>
