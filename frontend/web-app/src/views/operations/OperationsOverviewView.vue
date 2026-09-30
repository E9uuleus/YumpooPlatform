<script setup lang="ts">
import { Coin, Connection, Files, FolderOpened, Monitor, Tickets } from '@element-plus/icons-vue'
import type { OperationsAlert } from '@yumpoo/api-client'
import { computed, watch, type Component } from 'vue'
import { RouterLink } from 'vue-router'
import { ElAlert, ElIcon, ElSkeleton } from 'element-plus'
import { identityAdministrationApi } from '../../api/client'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
import { useOperations } from '../../components/operations/operationsContext'
import {
  EVENT_LABELS,
  bytes,
  metric,
  componentNames,
  moduleLabel,
  postureNames,
  ruleNames,
  time,
  duration,
} from '../../components/operations/operationsPresentation'
import { formatRelativeTime } from '../../design-system/dates'
import OpsTile from '../../components/operations/OpsTile.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
const query = useOperations(),
  data = query.data
const integration = useOperationsQuery(
  (signal) => identityAdministrationApi.getWeComIntegrationStatus({ signal }),
  { interval: () => query.interval.value * 1000 },
)
watch(query.refreshVersion, () => void integration.refresh())

type Tone = 'green' | 'yellow' | 'red'
const toneAbove = (value: number | null | undefined, warning: number, critical: number): Tone =>
  value == null ? 'green' : value >= critical ? 'red' : value >= warning ? 'yellow' : 'green'
const toneBelow = (value: number | null | undefined, warning: number, critical: number): Tone =>
  value == null ? 'green' : value <= critical ? 'red' : value <= warning ? 'yellow' : 'green'

const lowestVolume = computed(() =>
  (data.value?.runtime.host.volumes ?? [])
    .filter((v) => v.totalBytes > 0)
    .sort((a, b) => a.freeBytes / a.totalBytes - b.freeBytes / b.totalBytes)[0],
)
const tiles = computed(() => {
  const m = data.value?.runtime.metrics ?? {},
    disk = lowestVolume.value
  const diskRatio = disk ? disk.freeBytes / disk.totalBytes : null
  return [
    {
      name: '系统 CPU',
      key: 'cpu.system',
      value: metric(m['cpu.system'], 'RATIO'),
      unit: 'RATIO',
      tone: toneAbove(m['cpu.system'], 0.85, 0.95),
      detail: '进程 ' + metric(m['cpu.process'], 'RATIO'),
    },
    {
      name: '物理内存',
      key: 'memory.used',
      value: bytes(m['memory.used']),
      unit: 'BYTES',
      tone: 'green' as Tone,
      detail: '共 ' + bytes(m['memory.total']),
    },
    {
      name: 'JVM 堆',
      key: 'heap.used',
      value: bytes(m['heap.used']),
      unit: 'BYTES',
      tone: toneAbove(m['heap.ratio'], 0.85, 0.95),
      detail: '上限 ' + bytes(m['heap.max']),
    },
    {
      name: '磁盘剩余',
      key: disk ? 'disk.' + disk.id + '.free' : '',
      value: diskRatio == null ? '—' : metric(diskRatio, 'RATIO'),
      unit: 'BYTES',
      tone: toneBelow(diskRatio, 0.2, 0.1),
      detail: disk ? disk.label + ' · ' + bytes(disk.freeBytes) : '未知',
    },
    {
      name: '在线成员',
      key: 'sessions.online',
      value: metric(m['sessions.online']),
      suffix: '人',
      unit: 'COUNT',
      tone: 'green' as Tone,
      detail: '空闲 ' + metric(m['sessions.idle']),
    },
    {
      name: '请求',
      key: 'http.requests',
      value: metric(m['http.requests'] == null ? null : m['http.requests'] * 4),
      suffix: '次/分',
      unit: 'COUNT',
      tone: toneAbove(m['http.errorRate'], 0.01, 0.05),
      detail: '5xx ' + metric(m['http.errorRate'], 'RATIO') + ' · P95 ' + metric(m['http.p95'], 'MS'),
    },
  ]
})

const healthTone = { UP: 'green', DEGRADED: 'yellow', DOWN: 'red', UNKNOWN: 'gray' } as const
const banner = computed(() => {
  const runtime = data.value?.runtime
  if (!runtime) return undefined
  const health = (runtime.health in healthTone ? runtime.health : 'UNKNOWN') as keyof typeof healthTone
  const reasons: { label: string; to?: object; tone: 'yellow' | 'red' | 'gray' }[] = []
  runtime.components
    .filter((c) => c.status !== 'UP')
    .forEach((c) =>
      reasons.push({
        label: `${componentNames[c.code] ?? c.code}${c.status === 'DOWN' ? '不可用' : c.status === 'UNKNOWN' ? '状态未知' : '需要关注'}`,
        to: { name: 'operations-host' },
        tone: c.status === 'DOWN' ? 'red' : c.status === 'UNKNOWN' ? 'gray' : 'yellow',
      }),
    )
  runtime.posture
    .filter((p) => p.status === 'FAIL' && p.severity !== 'INFO')
    .forEach((p) =>
      reasons.push({
        label: '配置：' + (postureNames[p.code] ?? p.code),
        tone: p.severity === 'CRITICAL' ? 'red' : 'yellow',
      }),
    )
  data.value!.alerts.forEach((a) =>
    reasons.push({
      label: (ruleNames[a.ruleCode] ?? a.ruleCode) + (a.acknowledgedAt ? '（已确认）' : ''),
      to: { name: 'operations-alerts', query: { alert: a.id } },
      tone: a.severity === 'CRITICAL' ? 'red' : 'yellow',
    }),
  )
  const title = {
    UP: '系统运行正常',
    DEGRADED: `${reasons.length || 1} 项需要关注`,
    DOWN: '服务降级',
    UNKNOWN: '等待首次采样',
  }[health]
  return { tone: healthTone[health], title, reasons }
})
const alertCounts = computed(() => ({
  critical: data.value?.alerts.filter((a) => a.severity === 'CRITICAL').length ?? 0,
  warning: data.value?.alerts.filter((a) => a.severity === 'WARNING').length ?? 0,
}))

const componentIcons: Record<string, Component> = {
  application: Monitor,
  database: Coin,
  disk: Files,
  deploymentDirectories: FolderOpened,
  outbox: Tickets,
}
const componentReasons: Record<string, string> = {
  POOL_BUSY: '连接池已满',
  STALE: '采样已过期',
  DEPENDENCY_UNAVAILABLE: '依赖不可用',
  SAMPLING: '等待采样',
}
function componentDetail(code: string, reason: string): string {
  const m = data.value?.runtime.metrics ?? {}
  const parts: string[] = []
  if (componentReasons[reason]) parts.push(componentReasons[reason])
  if (code === 'application') parts.push('已运行 ' + duration(data.value?.runtime.host.uptimeMs ?? 0))
  if (code === 'database')
    parts.push(
      `Ping ${metric(m['db.ping'], 'MS')} · 连接 ${metric(m['db.pool.active'])}/${metric(m['db.pool.max'])} · 排队 ${metric(m['db.pool.pending'])}`,
    )
  if (code === 'disk' && lowestVolume.value)
    parts.push(
      `${lowestVolume.value.label} 剩余 ${bytes(lowestVolume.value.freeBytes)}（${metric(lowestVolume.value.freeBytes / lowestVolume.value.totalBytes, 'RATIO')}）`,
    )
  if (code === 'deploymentDirectories') parts.push('附件、上传临时与日志目录')
  if (code === 'outbox')
    parts.push(
      `积压 ${metric(m['outbox.backlog'])} · 最老等待 ${metric(m['outbox.oldestAge'], 'SECONDS')} · 投递失败 ${metric(m['outbox.dead'])}`,
    )
  return parts.join(' · ')
}
function alertSubtitle(alert: OperationsAlert): string {
  return `当前 ${metric(alert.lastValue, String(alert.params.unit))} · 已持续 ${duration(Date.now() - alert.startedAt.getTime())}`
}
const failedPosture = computed(() => data.value?.runtime.posture.filter((p) => p.status !== 'PASS') ?? [])
const passedPosture = computed(() => data.value?.runtime.posture.filter((p) => p.status === 'PASS') ?? [])
const postureTone = (severity: string) =>
  severity === 'CRITICAL' ? 'red' : severity === 'WARNING' ? 'yellow' : 'gray'
</script>

<template>
  <el-skeleton
    v-if="!data && query.loading.value"
    :rows="12"
    animated
  />
  <template v-if="data">
    <section
      v-if="banner"
      class="ops-banner"
      :class="`ops-tone-${banner.tone}`"
      aria-live="polite"
    >
      <span
        class="ops-banner__lamp"
        aria-hidden="true"
      />
      <div class="ops-banner__text">
        <strong>{{ banner.title }}</strong>
        <div class="ops-banner__reasons">
          <template
            v-for="reason in banner.reasons"
            :key="reason.label"
          >
            <router-link
              v-if="reason.to"
              class="ops-chip"
              :class="`ops-tone-${reason.tone}`"
              :to="reason.to"
            >
              {{ reason.label }}
            </router-link>
            <span
              v-else
              class="ops-chip"
              :class="`ops-tone-${reason.tone}`"
            >{{ reason.label }}</span>
          </template>
          <span
            v-if="!banner.reasons.length"
            class="ops-muted"
          >全部 {{ data.runtime.components.length }} 个组件健康，没有活跃告警。</span>
        </div>
        <span class="ops-muted">采样于 {{ time(data.runtime.sampledAt) }}</span>
      </div>
      <div class="ops-banner__counts">
        <div>
          <b class="ops-error">{{ alertCounts.critical }}</b><span>严重告警</span>
        </div>
        <div>
          <b class="ops-warning">{{ alertCounts.warning }}</b><span>警告告警</span>
        </div>
      </div>
    </section>
    <el-alert
      v-if="data.runtime.lostSamples"
      class="ops-overview-alert"
      type="warning"
      :closable="false"
      :title="`历史存储积压，已丢弃 ${data.runtime.lostSamples} 个分钟桶。图表保留缺口。`"
    />
    <el-alert
      v-if="data.runtime.metrics['alerts.droppedSamples']"
      class="ops-overview-alert"
      type="warning"
      :closable="false"
      :title="`告警评估积压，已丢弃 ${data.runtime.metrics['alerts.droppedSamples']} 份旧观测；部分历史告警可能缺失。`"
    />
    <div class="ops-kpis">
      <ops-tile
        v-for="tile in tiles"
        :key="tile.name"
        :label="tile.name"
        :value="tile.value"
        :unit="tile.suffix"
        :detail="tile.detail"
        :tone="tile.tone"
        :times="data.sparkline.map((p) => p.at)"
        :series="[
          {
            key: tile.key,
            unit: tile.unit,
            values: data.sparkline.map((p) => p.values[tile.key] ?? null),
          },
        ]"
      />
    </div>
    <div class="ops-grid main-side">
      <section class="ops-card flush">
        <header class="ops-card__head">
          <h2>服务组件</h2>
          <span class="ops-muted">数据库与部署目录来自健康检查，随概览刷新</span>
        </header>
        <ul class="ops-list">
          <li
            v-for="component in data.runtime.components"
            :key="component.code"
            class="ops-health-row"
          >
            <span class="ops-health-row__icon">
              <el-icon aria-hidden="true">
                <component :is="componentIcons[component.code] ?? Monitor" />
              </el-icon>
            </span>
            <span class="ops-health-row__name">{{ componentNames[component.code] ?? component.code }}</span>
            <yp-status-tag
              domain="operations"
              :status="component.status"
              effect="soft"
              size="small"
            />
            <span class="ops-health-row__detail">{{ componentDetail(component.code, component.detail) }}</span>
            <span class="ops-health-row__time">{{ component.checkedAt ? formatRelativeTime(component.checkedAt) : '—' }}</span>
          </li>
          <li class="ops-health-row">
            <span class="ops-health-row__icon">
              <el-icon aria-hidden="true"><connection /></el-icon>
            </span>
            <router-link
              class="ops-health-row__name ops-link"
              :to="{ name: 'company-overview' }"
            >
              企微集成
            </router-link>
            <yp-status-tag
              domain="integration"
              :status="integration.data.value?.oauth.configured ? 'CONFIGURED' : 'INCOMPLETE'"
              effect="soft"
              size="small"
            />
            <span class="ops-health-row__detail">最近同步成功 {{ time(integration.data.value?.lastSuccessfulRunAt) }}</span>
            <span class="ops-health-row__time" />
          </li>
        </ul>
      </section>
      <div class="ops-stack">
        <section class="ops-card flush">
          <header class="ops-card__head">
            <h2>活跃告警</h2>
            <router-link :to="{ name: 'operations-alerts' }">
              全部告警
            </router-link>
          </header>
          <ul
            v-if="data.alerts.length"
            class="ops-list"
          >
            <li
              v-for="alert in data.alerts"
              :key="alert.id"
            >
              <router-link
                class="ops-bar-row"
                :class="alert.severity === 'CRITICAL' ? 'ops-tone-red' : 'ops-tone-yellow'"
                :to="{ name: 'operations-alerts', query: { alert: alert.id } }"
              >
                <span>
                  <span class="ops-bar-row__title">{{ ruleNames[alert.ruleCode] ?? alert.ruleCode }}</span><br>
                  <span class="ops-bar-row__sub">{{ alertSubtitle(alert) }}</span>
                </span>
                <span class="ops-bar-row__sub">{{ alert.acknowledgedAt ? '已确认' : '未确认' }}</span>
              </router-link>
            </li>
          </ul>
          <yp-empty-state
            v-else
            compact
            title="没有活跃告警"
            description="12 条内置规则正在持续评估。"
          />
        </section>
        <section class="ops-card flush">
          <header class="ops-card__head">
            <h2>配置体检</h2>
            <span class="ops-muted">{{ passedPosture.length }} 项通过</span>
          </header>
          <ul class="ops-list ops-posture">
            <li
              v-for="check in failedPosture"
              :key="check.code"
            >
              <span
                class="ops-dot"
                :class="`ops-tone-${postureTone(check.severity)}`"
              />
              <span>{{ postureNames[check.code] ?? check.code }}</span>
              <yp-status-tag
                domain="operations"
                :status="check.status"
                effect="soft"
                size="small"
              />
            </li>
          </ul>
          <details class="ops-posture-passed">
            <summary class="ops-muted">
              {{ passedPosture.length }} 项通过
            </summary>
            <ul class="ops-list">
              <li
                v-for="check in passedPosture"
                :key="check.code"
              >
                {{ postureNames[check.code] ?? check.code }}
              </li>
            </ul>
          </details>
        </section>
      </div>
    </div>
    <div class="ops-grid">
      <section class="ops-card flush">
        <header class="ops-card__head">
          <h2>最近错误</h2>
          <router-link :to="{ name: 'operations-logs', query: { levels: 'ERROR' } }">
            在日志中查看
          </router-link>
        </header>
        <ul
          v-if="data.recentErrors.length"
          class="ops-list"
        >
          <li
            v-for="entry in data.recentErrors"
            :key="entry.id"
          >
            <router-link
              class="ops-error-row"
              :to="{
                name: 'operations-logs',
                query: { q: entry.record.event ?? '', requestId: entry.record.requestId ?? '' },
              }"
            >
              <span class="ops-lvl ops-lvl-ERROR">ERROR</span>
              <span class="ops-error-row__msg">
                <b>{{ EVENT_LABELS[entry.record.event ?? ''] ?? entry.record.msg }}</b>
                <span class="ops-code">{{ entry.record.error?.type.split('.').pop() ?? moduleLabel(entry.record.module) }}</span>
              </span>
              <span
                class="ops-muted"
                :title="time(entry.record.time)"
              >{{ formatRelativeTime(entry.record.time) }}</span>
            </router-link>
          </li>
        </ul>
        <yp-empty-state
          v-else
          compact
          title="没有错误日志"
          description="当前内存缓冲中没有 ERROR 级别记录。"
        />
      </section>
      <section class="ops-card">
        <h2>版本信息</h2>
        <dl class="ops-definition">
          <dt>应用版本</dt>
          <dd>{{ data.runtime.host.version }}</dd>
          <dt>Commit</dt>
          <dd class="ops-code">
            {{ data.runtime.host.commit }}
          </dd>
          <dt>构建时间</dt>
          <dd>{{ time(data.runtime.host.buildTime) }}</dd>
          <dt>迁移版本</dt>
          <dd>{{ data.runtime.host.schemaVersion }}</dd>
          <dt>Java / 环境</dt>
          <dd>
            {{ data.runtime.host.javaVersion }} ·
            {{ data.runtime.host.profiles.join(' · ') || 'default' }}
          </dd>
          <dt>启动时间</dt>
          <dd>
            {{ time(data.runtime.host.startedAt) }} · 已运行
            {{ duration(data.runtime.host.uptimeMs) }}
          </dd>
        </dl>
      </section>
    </div>
  </template>
</template>

<style scoped>
.ops-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-4);
  margin-bottom: var(--yp-space-4);
  padding: var(--yp-space-4) var(--yp-space-5);
  border: 1px solid color-mix(in srgb, var(--ops-tone) 40%, var(--yp-border-subtle));
  border-radius: var(--yp-radius-md);
  background: color-mix(in srgb, var(--ops-tone) 8%, var(--yp-bg-surface));
}

.ops-banner__lamp {
  flex: 0 0 auto;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--ops-tone);
  box-shadow: 0 0 0 5px color-mix(in srgb, var(--ops-tone) 22%, transparent);
}

.ops-banner__text {
  display: grid;
  flex: 1;
  gap: var(--yp-space-2);
  min-width: 220px;
}

.ops-banner__text strong {
  font: 500 18px / 26px var(--yp-font-heading);
}

.ops-banner__reasons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--yp-space-2);
}

.ops-banner__counts {
  display: flex;
  gap: var(--yp-space-5);
}

.ops-banner__counts div {
  display: grid;
  text-align: right;
}

.ops-banner__counts b {
  font: 500 22px / 28px var(--yp-font-heading);
  font-variant-numeric: tabular-nums;
}

.ops-banner__counts span {
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}

.ops-overview-alert {
  margin-bottom: var(--yp-space-3);
}

.ops-kpis {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: var(--yp-space-3);
  margin-bottom: var(--yp-space-5);
}

.ops-health-row {
  display: grid;
  grid-template-columns: 32px minmax(0, 130px) auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--yp-space-3);
  padding: var(--yp-space-3) var(--yp-space-4);
}

.ops-health-row__icon {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
}

.ops-health-row__name {
  font-weight: 600;
}

.ops-health-row__detail {
  overflow: hidden;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ops-health-row__time {
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  white-space: nowrap;
}

.ops-posture li {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--yp-space-2);
  padding: var(--yp-space-2) var(--yp-space-4);
  font-size: var(--yp-type-body-size);
}

.ops-posture-passed {
  padding: var(--yp-space-2) var(--yp-space-4) var(--yp-space-3);
}

.ops-posture-passed summary {
  cursor: pointer;
}

.ops-posture-passed li {
  padding: var(--yp-space-1) 0;
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}

.ops-error-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--yp-space-3);
  padding: var(--yp-space-3) var(--yp-space-4);
  color: inherit;
  text-decoration: none;
}

.ops-error-row:hover {
  background: var(--yp-bg-hover);
}

.ops-error-row__msg {
  display: flex;
  gap: var(--yp-space-2);
  align-items: baseline;
  min-width: 0;
  overflow: hidden;
  font-size: var(--yp-type-body-size);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ops-error-row__msg .ops-code {
  color: var(--yp-text-muted);
}

@media (max-width: 1280px) {
  .ops-kpis {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .ops-health-row {
    grid-template-columns: 32px minmax(0, 1fr) auto;
  }

  .ops-health-row__detail {
    grid-column: 2 / -1;
    white-space: normal;
  }

  .ops-health-row__time {
    display: none;
  }
}

@media (max-width: 640px) {
  .ops-kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
