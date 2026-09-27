<script setup lang="ts">
import { ElOption } from '../../components/operations/elementPlus'
import { ref, watch } from 'vue'
import { ElInput, ElSelect, ElTable, ElTableColumn, ElPagination, ElButton } from 'element-plus'
import { type ListOperationsSessionsPresenceEnum, type ListOperationsSessionsClientTypeEnum } from '@yumpoo/api-client'
import { operationsApi } from '../../api/client'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
import { useOperations } from '../../components/operations/operationsContext'
import { time } from '../../components/operations/operationsPresentation'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import InlineProblem from '../../components/InlineProblem.vue'
const presence = ref(''), clientType = ref(''), q = ref(''), page = ref(1)
const operations = useOperations()
const query = useOperationsQuery(signal => operationsApi.listOperationsSessions({ ...(presence.value ? { presence: presence.value as ListOperationsSessionsPresenceEnum } : {}), ...(clientType.value ? { clientType: clientType.value as ListOperationsSessionsClientTypeEnum } : {}), q:q.value, page:page.value-1, size:20 }, { signal }), { interval: () => operations.interval.value * 1000 })
const summary = useOperationsQuery(signal => operationsApi.getOperationsSessionSummary({ signal }), { interval: () => operations.interval.value * 1000 })
watch(operations.refreshVersion, () => { void query.refresh(); void summary.refresh() })
function search() { page.value = 1; void query.refresh() }
watch([presence,clientType], search); watch(page, () => void query.refresh())
</script>
<template>
  <div class="ops-grid three">
    <section
      v-for="item in [{ title:'在线',value:summary.data.value?.online },{title:'空闲',value:summary.data.value?.idle},{title:'离开',value:summary.data.value?.away}]"
      :key="item.title"
      class="ops-card"
    >
      <span class="ops-muted">{{ item.title }}</span><div class="ops-number">
        {{ item.value ?? '—' }}
      </div>
    </section>
  </div>
  <p class="ops-muted">
    在线：最近 2 分钟活跃；空闲：最近 30 分钟活跃；离开：其余有效会话。仅在页面可见且未编辑输入时自动刷新。
  </p>
  <div class="ops-toolbar">
    <el-input
      v-model="q"
      placeholder="搜索成员名称"
      clearable
      :maxlength="200"
      aria-label="搜索成员名称"
      @keyup.enter="search"
    />
    <el-select
      v-model="presence"
      aria-label="成员状态"
    >
      <el-option
        label="全部状态"
        value=""
      /><el-option
        label="在线"
        value="ONLINE"
      /><el-option
        label="空闲"
        value="IDLE"
      /><el-option
        label="离开"
        value="AWAY"
      />
    </el-select>
    <el-select
      v-model="clientType"
      aria-label="客户端"
    >
      <el-option
        label="全部客户端"
        value=""
      /><el-option
        label="Web"
        value="WEB"
      /><el-option
        label="Electron"
        value="ELECTRON"
      />
    </el-select><el-button @click="search">
      查询
    </el-button>
  </div>
  <inline-problem
    v-if="query.error.value"
    :problem="query.error.value"
  />
  <el-table
    :data="query.data.value?.items ?? []"
    row-key="userId"
    empty-text="暂无匹配的有效会话"
  >
    <el-table-column type="expand">
      <template #default="{ row }">
        <div class="ops-card">
          <div
            v-for="session in row.sessions"
            :key="session.id"
            class="ops-row"
          >
            <strong>{{ session.clientType }} {{ session.clientVersion ?? '未知版本' }}</strong><span>登录 {{ time(session.issuedAt) }} · 最近活跃 {{ time(session.lastSeenAt) }} · 到期 {{ time(session.expiresAt) }}</span>
          </div>
        </div>
      </template>
    </el-table-column>
    <el-table-column
      prop="displayName"
      label="成员"
    /><el-table-column label="状态">
      <template #default="{ row }">
        <yp-status-tag
          domain="operations"
          :status="row.presence"
          effect="soft"
        />
      </template>
    </el-table-column>
    <el-table-column label="会话数">
      <template #default="{ row }">
        {{ row.sessions.length }}
      </template>
    </el-table-column><el-table-column label="最近活跃">
      <template #default="{ row }">
        {{ time(row.lastSeenAt) }}
      </template>
    </el-table-column>
  </el-table>
  <el-pagination
    v-model:current-page="page"
    class="ops-pagination"
    :page-size="20"
    :total="query.data.value?.totalElements ?? 0"
    layout="total, prev, pager, next"
  />
  <section
    v-if="summary.data.value"
    class="ops-card versions"
  >
    <h2>客户端版本分布 · {{ summary.data.value.activeSessions }} 个有效会话</h2><div
      v-for="version in summary.data.value.byClientVersion"
      :key="version.clientType + version.clientVersion"
      class="ops-row"
    >
      <span>{{ version.clientType }} · {{ version.clientVersion ?? '未知版本' }}</span><strong>{{ version.count }}</strong>
    </div>
  </section>
</template>
<style scoped>.versions{margin-top:var(--yp-space-5)}</style>

