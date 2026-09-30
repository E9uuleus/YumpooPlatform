<script setup lang="ts">
import { ElOption, ElTabs } from '../../components/operations/elementPlus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElDrawer,
  ElInput,
  ElInputNumber,
  ElPagination,
  ElSelect,
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
import { operationsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
import {
  duration,
  eventNames,
  metric,
  ruleNames,
  time,
  postureNames,
} from '../../components/operations/operationsPresentation'
import { useOperations } from '../../components/operations/operationsContext'
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
const selected = ref<OperationsAlert>(),
  drawer = ref(false),
  ack = ref<OperationsAlert>(),
  note = ref('')
const detail = useOperationsQuery(
  (signal) =>
    operationsApi.getOperationsAlert(
      { alertId: selected.value?.id ?? String(route.query.alert) },
      { signal },
    ),
  { enabled: () => drawer.value },
)
function open(alert: OperationsAlert) {
  selected.value = alert
  drawer.value = true
  void detail.refresh()
}
if (typeof route.query.alert === 'string') drawer.value = true
let disposed = false
const mutationController = new AbortController()
onBeforeUnmount(() => {
  disposed = true
  mutationController.abort()
})
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
    if (drawer.value) void detail.refresh()
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
      label="活跃告警"
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
      class="ops-card alert-card"
      :class="{ critical: alert.severity === 'CRITICAL' }"
    >
      <div class="ops-toolbar">
        <yp-status-tag
          domain="operations"
          :status="alert.severity"
          effect="soft"
        /><strong>{{
          ruleNames[alert.ruleCode] ?? alert.ruleCode
        }}</strong><span class="ops-muted">{{ subject(alert) }}</span>
      </div>
      <p>
        当前 {{ metric(alert.lastValue, String(alert.params.unit)) }} · 触发阈值
        {{ metric(alertThreshold(alert), String(alert.params.unit)) }} · 极值
        {{ metric(alert.peakValue, String(alert.params.unit)) }} · 已持续
        {{ duration(Date.now() - alert.startedAt.getTime()) }}
      </p>
      <operations-chart
        v-if="alertSeries(alert).length"
        :title="ruleNames[alert.ruleCode] + '最近一小时趋势'"
        :times="trends.data.value?.timestamps ?? []"
        :series="alertSeries(alert)"
        :threshold="alertThreshold(alert)"
        compact
      />
      <div class="ops-toolbar">
        <span
          class="ops-muted"
          :title="alert.acknowledgedByUserId ?? undefined"
        >{{
          alert.acknowledgedAt ? '已确认 · ' + time(alert.acknowledgedAt) : '未确认'
        }}</span><el-button
          v-if="!alert.acknowledgedAt"
          size="small"
          @click="
            ack = alert;
            note = ''
          "
        >
          确认
        </el-button><el-button
          size="small"
          @click="open(alert)"
        >
          详情与时间线
        </el-button><router-link :to="{ name: 'operations-host' }">
          查看指标
        </router-link><router-link :to="logLink(alert)">
          查看日志
        </router-link>
      </div>
    </article>
    <div
      v-if="!active.length && !query.loading.value"
      class="ops-empty"
    >
      暂无活跃告警
    </div>
  </template>
  <el-table
    v-if="tab === 'history'"
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
        />
      </template>
    </el-table-column><el-table-column label="规则">
      <template #default="{ row }">
        {{ ruleNames[row.ruleCode] ?? row.ruleCode }}
      </template>
    </el-table-column>
    <el-table-column label="开始">
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
  <el-pagination
    v-if="tab !== 'rules'"
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
    <p class="ops-muted">
      比例使用百分比；磁盘以剩余比例低于阈值触发，其他规则以达到阈值触发。恢复需连续正常 1 分钟。
    </p>
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
        min-width="180"
      >
        <template #default="{ row }">
          {{ ruleNames[row.code] ?? row.code }}
          <div class="ops-muted">
            {{
              row.unit === 'RATIO'
                ? '%'
                : row.unit === 'MS'
                  ? '毫秒'
                  : row.unit === 'SECONDS'
                    ? '秒'
                    : '次'
            }}
            · {{ row.comparison === 'BELOW' ? '低于' : '达到' }}
          </div>
        </template>
      </el-table-column>
      <el-table-column
        label="警告阈值"
        width="170"
      >
        <template #default="{ row }">
          <el-input-number
            v-if="row.warningThreshold != null"
            :model-value="row.warningThreshold * (row.unit === 'RATIO' ? 100 : 1)"
            :precision="row.unit === 'RATIO' ? 2 : 0"
            :min="row.unit === 'RATIO' ? 0.01 : 1"
            :max="row.unit === 'RATIO' ? 100 : 1000000000"
            :aria-label="'警告阈值 ' + ruleNames[row.code]"
            @update:model-value="threshold(row as OperationsRule, 'warningThreshold', $event)"
          /><span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column
        label="严重阈值"
        width="170"
      >
        <template #default="{ row }">
          <el-input-number
            :model-value="row.criticalThreshold * (row.unit === 'RATIO' ? 100 : 1)"
            :precision="row.unit === 'RATIO' ? 2 : 0"
            :min="row.unit === 'RATIO' ? 0.01 : 1"
            :max="row.unit === 'RATIO' ? 100 : 1000000000"
            :aria-label="'严重阈值 ' + ruleNames[row.code]"
            @update:model-value="threshold(row as OperationsRule, 'criticalThreshold', $event)"
          />
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
        width="85"
      >
        <template #default="{ row }">
          <el-button
            size="small"
            :loading="saving === row.code"
            :disabled="!!saving || baselines.get(row.code) === signature(row as OperationsRule)"
            @click="save(row as OperationsRule)"
          >
            保存
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </template>
  <el-dialog
    :model-value="!!ack"
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
  <el-drawer
    v-model="drawer"
    title="告警详情"
    size="min(640px, 95vw)"
  >
    <inline-problem
      v-if="detail.error.value"
      :problem="detail.error.value"
    />
    <template v-if="detail.data.value">
      <h2>{{ ruleNames[detail.data.value.alert.ruleCode] }}</h2>
      <p class="ops-muted">
        {{ detail.data.value.alert.subjectKey }} · {{ time(detail.data.value.alert.startedAt) }}
      </p>
      <p>{{ detail.data.value.alert.acknowledgeNote }}</p>
      <div
        v-for="item in detail.data.value.events"
        :key="item.id"
        class="ops-row"
      >
        <span>{{ eventNames[item.eventType] ?? item.eventType }}
          <div class="ops-muted">{{ time(item.occurredAt) }}<br>{{ item.actorUserId }}</div></span><strong>{{ metric(item.value, String(detail.data.value.alert.params.unit)) }}</strong>
      </div>
    </template>
  </el-drawer>
</template>
<style scoped>
.alert-card {
  border-left: 4px solid var(--yp-status-yellow);
  margin-bottom: var(--yp-space-3);
}
.alert-card.critical {
  border-left-color: var(--yp-status-red);
}
.el-input-number {
  width: 140px;
}
</style>
