<script setup lang="ts">
import { computed, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ElAlert, ElSkeleton } from 'element-plus'
import { identityAdministrationApi } from '../../api/client'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
import { useOperations } from '../../components/operations/operationsContext'
import {
  bytes,
  metric,
  componentNames,
  postureNames,
  ruleNames,
  time,
  duration,
} from '../../components/operations/operationsPresentation'
import OperationsChart from '../../components/operations/OperationsChart.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
const query = useOperations(),
  data = query.data
const integration = useOperationsQuery(
  (signal) => identityAdministrationApi.getWeComIntegrationStatus({ signal }),
  { interval: () => query.interval.value * 1000 },
)
watch(query.refreshVersion, () => void integration.refresh())
const cards = computed(() => {
  const m = data.value?.runtime.metrics ?? {},
    volumes = data.value?.runtime.host.volumes ?? []
  const disk = volumes
    .filter((v) => v.totalBytes > 0)
    .sort((a, b) => a.freeBytes / a.totalBytes - b.freeBytes / b.totalBytes)[0]
  return [
    {
      name: '系统 CPU',
      key: 'cpu.system',
      value: metric(m['cpu.system'], 'RATIO'),
      unit: 'RATIO',
      attention: (m['cpu.system'] ?? 0) >= 0.85,
    },
    {
      name: '物理内存',
      key: 'memory.used',
      value: bytes(m['memory.used']),
      unit: 'BYTES',
      detail: '共 ' + bytes(m['memory.total']),
    },
    {
      name: 'JVM 堆',
      key: 'heap.used',
      value: bytes(m['heap.used']),
      unit: 'BYTES',
      attention: (m['heap.ratio'] ?? 0) >= 0.85,
      detail: '上限 ' + bytes(m['heap.max']),
    },
    {
      name: '磁盘剩余',
      key: disk ? 'disk.' + disk.id + '.free' : '',
      value: disk ? metric(disk.freeBytes / disk.totalBytes, 'RATIO') : '—',
      unit: 'BYTES',
      attention: disk ? disk.freeBytes / disk.totalBytes <= 0.2 : false,
      detail: disk ? disk.label + ' · ' + bytes(disk.freeBytes) : '未知',
    },
    {
      name: '在线成员',
      key: 'sessions.online',
      value: metric(m['sessions.online']),
      unit: 'COUNT',
    },
    {
      name: '请求 / 分钟',
      key: 'http.requests',
      value: metric(m['http.requests'] == null ? null : m['http.requests'] * 4),
      unit: 'COUNT',
      detail: '5xx ' + metric(m['http.errorRate'], 'RATIO'),
    },
  ]
})
</script>
<template>
  <el-skeleton
    v-if="!data && query.loading.value"
    :rows="12"
    animated
  />
  <template v-if="data">
    <div class="ops-toolbar">
      <yp-status-tag
        domain="operations"
        :status="data.runtime.health"
        effect="soft"
      /><span
        class="ops-muted"
      >采样于 {{ time(data.runtime.sampledAt) }} · 5xx
        {{ metric(data.runtime.metrics['http.errorRate'], 'RATIO') }}</span>
    </div>
    <el-alert
      v-if="data.runtime.lostSamples"
      type="warning"
      :closable="false"
      :title="`历史存储积压，已丢弃 ${data.runtime.lostSamples} 个分钟桶。图表保留缺口。`"
    />
    <el-alert
      v-if="data.runtime.metrics['alerts.droppedSamples']"
      type="warning"
      :closable="false"
      :title="`告警评估积压，已丢弃 ${data.runtime.metrics['alerts.droppedSamples']} 份旧观测；部分历史告警可能缺失。`"
    />
    <div class="ops-kpis">
      <article
        v-for="card in cards"
        :key="card.name"
        class="ops-card"
      >
        <span class="ops-muted">{{ card.name }}</span>
        <div
          class="ops-number"
          :class="{ 'ops-warning': card.attention }"
        >
          {{ card.value }}
        </div>
        <small class="ops-muted">{{ card.detail ?? '最近 1 小时' }}</small>
        <operations-chart
          :title="card.name + '近一小时'"
          :times="data.sparkline.map((p) => p.at)"
          :series="[
            {
              key: card.key,
              unit: card.unit,
              values: data.sparkline.map((p) => p.values[card.key] ?? null),
            },
          ]"
          compact
        />
      </article>
    </div>
    <div class="ops-grid">
      <section class="ops-card">
        <h2>服务组件</h2>
        <div
          v-for="component in data.runtime.components"
          :key="component.code"
          class="ops-row"
        >
          <div>
            {{ componentNames[component.code] ?? component.code }}
            <div class="ops-muted">
              {{
                component.code === 'database'
                  ? 'Ping ' + metric(data.runtime.metrics['db.ping'], 'MS')
                  : ''
              }}
              · {{ time(component.checkedAt) }}
            </div>
          </div>
          <yp-status-tag
            domain="operations"
            :status="component.status"
            effect="soft"
          />
        </div>
        <div class="ops-row">
          <router-link :to="{ name: 'company-overview' }">
            企微集成状态
          </router-link><yp-status-tag
            domain="integration"
            :status="integration.data.value?.oauth.configured ? 'CONFIGURED' : 'INCOMPLETE'"
            effect="soft"
          />
        </div>
      </section>
      <div class="ops-stack">
        <section class="ops-card">
          <h2>
            活跃告警 <router-link :to="{ name: 'operations-alerts' }">
              查看全部
            </router-link>
          </h2>
          <div
            v-for="alert in data.alerts"
            :key="alert.id"
            class="ops-row"
          >
            <router-link :to="{ name: 'operations-alerts', query: { alert: alert.id } }">
              {{ ruleNames[alert.ruleCode] ?? alert.ruleCode }}
            </router-link><yp-status-tag
              domain="operations"
              :status="alert.severity"
              effect="soft"
            />
          </div>
          <div
            v-if="!data.alerts.length"
            class="ops-muted"
          >
            暂无活跃告警
          </div>
        </section>
        <section class="ops-card">
          <h2>配置体检</h2>
          <div
            v-for="check in data.runtime.posture.filter((p) => p.status !== 'PASS')"
            :key="check.code"
            class="ops-row"
          >
            <span>{{ postureNames[check.code] ?? check.code }}</span><yp-status-tag
              domain="operations"
              :status="check.status"
              effect="soft"
            />
          </div>
          <details>
            <summary class="ops-muted">
              {{ data.runtime.posture.filter((p) => p.status === 'PASS').length }} 项通过
            </summary>
            <div
              v-for="check in data.runtime.posture.filter((p) => p.status === 'PASS')"
              :key="check.code"
              class="ops-row"
            >
              {{ postureNames[check.code] ?? check.code }}
            </div>
          </details>
        </section>
      </div>
    </div>
    <section class="ops-card">
      <h2>
        最近错误
        <router-link :to="{ name: 'operations-logs', query: { levels: 'ERROR' } }">
          查看日志
        </router-link>
      </h2>
      <div
        v-for="entry in data.recentErrors"
        :key="entry.id"
        class="ops-row ops-code"
      >
        <span>{{ time(entry.record.time) }} · {{ entry.record.module }}</span><router-link
          :to="{
            name: 'operations-logs',
            query: { q: entry.record.event ?? '', requestId: entry.record.requestId ?? '' },
          }"
        >
          {{ entry.record.msg }}
        </router-link>
      </div>
      <div
        v-if="!data.recentErrors.length"
        class="ops-muted"
      >
        当前缓冲区没有错误日志
      </div>
    </section>
    <section class="ops-card version-card">
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
  </template>
</template>
<style scoped>
.ops-kpis {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: var(--yp-space-3);
  margin-bottom: var(--yp-space-5);
}
h2 a {
  float: right;
  font-size: 12px;
  font-weight: 400;
}
.ops-kpis .ops-card {
  padding: var(--yp-space-4);
}
@media (max-width: 1280px) {
  .ops-kpis {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .ops-kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
