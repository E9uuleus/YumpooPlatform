<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElInput, ElTable, ElTableColumn, ElPagination, ElButton } from 'element-plus'
import {
  type ListOperationsSessionsPresenceEnum,
  type ListOperationsSessionsClientTypeEnum,
} from '@yumpoo/api-client'
import { operationsApi } from '../../api/client'
import { useOperationsQuery } from '../../composables/useOperationsQuery'
import { useOperations } from '../../components/operations/operationsContext'
import { time } from '../../components/operations/operationsPresentation'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import OpsSegmented from '../../components/operations/OpsSegmented.vue'
import { formatRelativeTime } from '../../design-system/dates'
import InlineProblem from '../../components/InlineProblem.vue'
const presence = ref(''),
  clientType = ref(''),
  q = ref(''),
  page = ref(1)
const operations = useOperations()
const query = useOperationsQuery(
  (signal) =>
    operationsApi.listOperationsSessions(
      {
        ...(presence.value
          ? { presence: presence.value as ListOperationsSessionsPresenceEnum }
          : {}),
        ...(clientType.value
          ? { clientType: clientType.value as ListOperationsSessionsClientTypeEnum }
          : {}),
        q: q.value,
        page: page.value - 1,
        size: 20,
      },
      { signal },
    ),
  { interval: () => operations.interval.value * 1000 },
)
const summary = useOperationsQuery(
  (signal) => operationsApi.getOperationsSessionSummary({ signal }),
  { interval: () => operations.interval.value * 1000 },
)
watch(operations.refreshVersion, () => {
  void query.refresh()
  void summary.refresh()
})
const presenceOptions = [
  { value: '', label: '全部' },
  { value: 'ONLINE', label: '在线' },
  { value: 'IDLE', label: '空闲' },
  { value: 'AWAY', label: '离开' },
]
const clientOptions = [
  { value: '', label: '全部客户端' },
  { value: 'WEB', label: 'Web' },
  { value: 'ELECTRON', label: 'Electron' },
]
function clientLabel(type: string, version?: string | null): string {
  return type === 'WEB' ? 'Web' : type === 'ELECTRON' ? `Electron ${version ?? ''}`.trim() : type
}
function search() {
  page.value = 1
  void query.refresh()
}
watch([presence, clientType], search)
watch(page, () => void query.refresh())
</script>
<template>
  <section
    class="ops-strip"
    aria-label="会话摘要"
  >
    <div
      v-for="item in [
        { title: '在线', hint: '2 分钟内有请求', value: summary.data.value?.online },
        { title: '空闲', hint: '30 分钟内有请求', value: summary.data.value?.idle },
        { title: '离开', hint: '其余有效会话', value: summary.data.value?.away },
        { title: '有效会话', hint: '未过期、未撤销', value: summary.data.value?.activeSessions },
      ]"
      :key="item.title"
    >
      <span class="ops-number">{{ item.value ?? '—' }}</span>
      <span class="ops-muted">{{ item.title }} · {{ item.hint }}</span>
    </div>
    <div class="wide">
      <span class="ops-muted">客户端版本</span>
      <div class="session-versions">
        <span
          v-for="version in summary.data.value?.byClientVersion ?? []"
          :key="version.clientType + version.clientVersion"
          class="ops-chip"
        >{{ clientLabel(version.clientType, version.clientVersion ?? '未知版本') }} × {{ version.count }}</span>
        <span
          v-if="!summary.data.value?.byClientVersion.length"
          class="ops-muted"
        >—</span>
      </div>
    </div>
  </section>
  <div class="ops-toolbar">
    <el-input
      v-model="q"
      placeholder="搜索成员名称"
      clearable
      :maxlength="200"
      aria-label="搜索成员名称"
      @keyup.enter="search"
    />
    <el-button @click="search">
      查询
    </el-button>
    <ops-segmented
      group-label="成员状态"
      :options="presenceOptions"
      :selected="presence"
      @select="presence = String($event)"
    />
    <ops-segmented
      group-label="客户端"
      :options="clientOptions"
      :selected="clientType"
      @select="clientType = String($event)"
    />
    <span class="ops-muted ops-toolbar__end">{{ query.data.value?.totalElements ?? 0 }} 位成员 · 仅在页面可见且未编辑输入时自动刷新</span>
  </div>
  <inline-problem
    v-if="query.error.value"
    :problem="query.error.value"
  />
  <section class="ops-card flush">
    <el-table
      :data="query.data.value?.items ?? []"
      row-key="userId"
      empty-text="暂无匹配的有效会话"
    >
      <el-table-column type="expand">
        <template #default="{ row }">
          <ul class="ops-list session-detail">
            <li
              v-for="session in row.sessions"
              :key="session.id"
            >
              <span class="ops-chip">{{ clientLabel(session.clientType, session.clientVersion ?? '未知版本') }}</span>
              <span class="ops-muted">登录 {{ time(session.issuedAt) }} · 最近活跃 {{ time(session.lastSeenAt) }} · 到期
                {{ time(session.expiresAt) }}</span>
            </li>
          </ul>
        </template>
      </el-table-column>
      <el-table-column
        label="成员"
        min-width="180"
      >
        <template #default="{ row }">
          <yp-assignee
            :user-id="row.userId"
            :display-name="row.displayName"
            size="table"
          />
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <yp-status-tag
            domain="operations"
            :status="row.presence"
            effect="soft"
            size="small"
          />
        </template>
      </el-table-column>
      <el-table-column
        label="客户端"
        min-width="200"
      >
        <template #default="{ row }">
          <div class="session-clients">
            <span
              v-for="session in row.sessions"
              :key="session.id"
              class="ops-chip"
            >{{ clientLabel(session.clientType, session.clientVersion) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column
        label="最近活跃"
        width="140"
      >
        <template #default="{ row }">
          <span :title="time(row.lastSeenAt)">{{ formatRelativeTime(row.lastSeenAt) }}</span>
        </template>
      </el-table-column>
    </el-table>
    <footer
      v-if="(query.data.value?.totalElements ?? 0) > 0"
      class="ops-card__foot"
    >
      <el-pagination
        v-model:current-page="page"
        class="ops-pagination"
        :page-size="20"
        :total="query.data.value?.totalElements ?? 0"
        layout="total, prev, pager, next"
      />
    </footer>
  </section>
</template>

<style scoped>
.ops-strip .ops-number {
  display: block;
}

.session-versions,
.session-clients {
  display: flex;
  flex-wrap: wrap;
  gap: var(--yp-space-2);
}

.session-versions {
  margin-top: var(--yp-space-1);
}

.session-detail {
  margin: 0 var(--yp-space-5);
}

.session-detail li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-3);
  padding: var(--yp-space-2) 0;
}
</style>
