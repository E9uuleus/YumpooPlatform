<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { ElButton, ElDialog, ElIcon, ElMessage, ElPopconfirm } from 'element-plus'
import { CollectionTag, Flag, List, User } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import type { WorkItemConnection } from '@yumpoo/api-client'
import { isProblemStatus, type ApiProblem } from '../../../api/problems'
import { useSession } from '../../../composables/useSession'
import { formatTimestamp } from '../../../design-system/dates'
import InlineProblem from '../../InlineProblem.vue'
import YpAssignee from '../../yp/YpAssignee.vue'
import { workItemLabelColorValue } from '../workItemLabelColors'
import { toConnectProblem } from './connectProblems'
import { useConnectContext } from './useConnectColumns'

const props = defineProps<{ open: boolean; connection: WorkItemConnection; perspective: 'source' | 'target' }>()
const emit = defineEmits<{ 'update:open': [open: boolean]; unlinked: [connection: WorkItemConnection]; invalidated: [connection: WorkItemConnection] }>()
const context = useConnectContext(), router = useRouter(), session = useSession()
const current = shallowRef(props.connection), loading = ref(false), busy = ref(false), problem = ref<ApiProblem>()
const card = computed(() => props.perspective === 'source' ? current.value.target : current.value.source)
const createdAt = computed(() => formatTimestamp(current.value.createdAt, session.authentication.value?.company.timezone ?? 'UTC'))
const overview = computed(() => ({ path: `/projects/${card.value.projectId}/overview`, query: { view: 'table' } }))
let controller: AbortController | undefined, revision = 0
async function refresh() {
  controller?.abort(); controller = new AbortController()
  const requestRevision = ++revision
  loading.value = true; problem.value = undefined
  try {
    const result = await context.getConnection(props.connection.id, controller.signal)
    if (requestRevision !== revision) return
    if (!result.active) { missing(); return }
    current.value = result
  } catch (reason) {
    const failure = await toConnectProblem(reason)
    if (requestRevision !== revision) return
    if (isProblemStatus(failure, 404)) missing(); else problem.value = failure
  } finally { if (requestRevision === revision) loading.value = false }
}
function missing() { ElMessage.info('该连接已不存在'); emit('invalidated', props.connection); emit('update:open', false) }
watch(() => [props.open, props.connection.id], () => {
  revision++; controller?.abort()
  if (props.open) { current.value = props.connection; void refresh() }
}, { immediate: true })
onBeforeUnmount(() => { revision++; controller?.abort() })
function close() { if (!busy.value) emit('update:open', false) }
async function unlink() {
  if (busy.value || loading.value || !current.value.capabilities.canUnlink) return
  busy.value = true; problem.value = undefined
  try { await context.unlink(current.value); emit('unlinked', current.value); emit('update:open', false); ElMessage.success('已解除连接') }
  catch (reason) { problem.value = await toConnectProblem(reason); if (isProblemStatus(problem.value, 404)) missing() }
  finally { busy.value = false }
}
async function openWorkItem() { if (card.value.canOpen) { await router.push({ ...overview.value, query: { ...overview.value.query, workItemId: card.value.workItemId } }); close() } }
</script>

<template>
  <el-dialog
    :model-value="open"
    class="connection-card-dialog"
    width="min(640px, calc(100vw - 32px))"
    align-center
    append-to-body
    destroy-on-close
    :show-close="!busy"
    :close-on-press-escape="!busy"
    :before-close="close"
    @update:model-value="close"
  >
    <template #header="{ titleId }">
      <div class="connection-card-dialog__heading">
        <h2 :id="titleId">
          {{ card.title }}
        </h2><code>{{ card.itemNo }}</code><span
          v-if="card.archived"
          class="connection-card-dialog__archived"
        >已归档</span>
      </div>
      <p class="connection-card-dialog__project">
        所在 → <router-link
          v-if="card.canOpen"
          :to="overview"
          @click="close"
        >
          {{ card.projectName }}
        </router-link><span v-else>{{ card.projectName }}</span>
      </p>
    </template>
    <inline-problem
      v-if="problem"
      :problem="problem"
    />
    <el-button
      v-if="problem"
      text
      @click="refresh"
    >
      重新加载卡片
    </el-button>
    <dl
      class="connection-card-fields"
      :aria-busy="loading"
    >
      <dt>
        <el-icon class="connection-card-fields__icon connection-card-fields__icon--category">
          <collection-tag />
        </el-icon>工作项类别
      </dt>
      <dd
        class="connection-card-fields__label"
        :style="{ background: workItemLabelColorValue(card.category.colorToken) }"
      >
        {{ card.category.name }}
      </dd>
      <dt>
        <el-icon class="connection-card-fields__icon connection-card-fields__icon--assignee">
          <user />
        </el-icon>处理人
      </dt>
      <dd>
        <yp-assignee
          v-if="card.assignee"
          :user-id="card.assignee.userId"
          :display-name="card.assignee.displayName"
          size="table"
        /><span v-else>未分配</span>
      </dd>
      <dt>
        <el-icon class="connection-card-fields__icon connection-card-fields__icon--status">
          <list />
        </el-icon>状态
      </dt>
      <dd
        class="connection-card-fields__label"
        :style="{ background: workItemLabelColorValue(card.status.colorToken) }"
      >
        {{ card.status.name }}
      </dd>
      <dt>
        <el-icon class="connection-card-fields__icon connection-card-fields__icon--priority">
          <flag />
        </el-icon>优先级
      </dt>
      <dd
        :class="{ 'connection-card-fields__label': card.priority }"
        :style="card.priority ? { background: workItemLabelColorValue(card.priority.colorToken) } : undefined"
      >
        {{ card.priority?.name ?? '—' }}
      </dd>
    </dl>
    <p class="connection-card-dialog__meta">
      连接于「{{ current.columnName }}」· {{ current.createdBy.displayName }} · {{ createdAt }}
    </p>
    <template #footer>
      <el-popconfirm
        v-if="current.capabilities.canUnlink"
        title="解除连接？对应工作项不会被删除。"
        confirm-button-text="解除连接"
        cancel-button-text="取消"
        @confirm="unlink"
      >
        <template #reference>
          <el-button
            text
            type="danger"
            :disabled="loading"
            :loading="busy"
          >
            解除连接
          </el-button>
        </template>
      </el-popconfirm>
      <el-button
        v-if="card.canOpen"
        type="primary"
        @click="openWorkItem"
      >
        打开工作项 →
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.connection-card-dialog__heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; padding-right: 24px; }
.connection-card-dialog__heading h2 { margin: 0; color: var(--yp-text-primary); font-family: var(--yp-font-heading); font-size: 20px; font-weight: 600; line-height: 1.5; overflow-wrap: anywhere; }
.connection-card-dialog__heading code { color: var(--yp-text-muted); font-size: 12px; white-space: nowrap; }
.connection-card-dialog__archived { border-radius: var(--yp-radius-sm); padding: 2px 6px; background: var(--yp-bg-sunken); color: var(--yp-text-muted); font-size: 12px; }
.connection-card-dialog__project { margin: 8px 0 0; color: var(--yp-text-secondary); font-size: 13px; }
.connection-card-dialog__project a { color: var(--yp-action-primary); text-decoration: none; }
.connection-card-fields { display: grid; grid-template-columns: 140px minmax(0, 1fr); align-items: center; gap: 8px 12px; margin: 16px 0; }
.connection-card-fields dt { display: flex; align-items: center; gap: 8px; color: var(--yp-text-secondary); font-size: 13px; }
.connection-card-fields__icon { width: 24px; height: 24px; flex-shrink: 0; border-radius: var(--yp-radius-sm); color: var(--yp-text-inverse); }
.connection-card-fields__icon--category { background: var(--yp-label-egg-yolk); }
.connection-card-fields__icon--assignee { background: var(--yp-label-chili-blue); }
.connection-card-fields__icon--status { background: var(--yp-label-green); }
.connection-card-fields__icon--priority { background: var(--yp-label-orange); }
.connection-card-fields dd { display: flex; align-items: center; justify-content: center; min-width: 0; height: 40px; margin: 0; padding: 0 8px; border-radius: var(--yp-radius-sm); background: var(--yp-bg-sunken); color: var(--yp-text-secondary); font-size: 13px; }
.connection-card-fields .connection-card-fields__label { color: var(--yp-text-inverse); font-weight: 500; }
.connection-card-dialog__meta { margin: 12px 0 0; color: var(--yp-text-muted); font-size: 12px; line-height: 1.7; }
@media (max-width: 480px) { .connection-card-fields { grid-template-columns: 110px minmax(0, 1fr); gap: 8px; } }
</style>

<style>
.connection-card-dialog.el-dialog { border-radius: var(--yp-radius-xl); }
</style>
