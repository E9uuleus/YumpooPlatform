<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElIcon, ElTable, ElTableColumn } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import type { TeamMemberTask, TeamWorkload } from '@yumpoo/api-client'
import YpAssignee from '../../yp/YpAssignee.vue'
import { workItemLabelColorValue } from '../../projects/workItemLabelColors'
import type { MemberTasks } from './useTeamDashboard'

const props = defineProps<{ workload?: TeamWorkload | undefined; loading: boolean; error: string; tasks: Map<string, MemberTasks> }>()
const emit = defineEmits<{ retry: []; load: [key: string, append: boolean] }>()
const router = useRouter()
const expanded = ref(new Set<string>()), showIdle = ref(false)
interface Row { key: string; userId: string | null; name: string; active: boolean; todo: number; inProgress: number; overdue: number; total: number }
const rows = computed<Row[]>(() => {
  const members = (props.workload?.members ?? []).map(m => ({ key: m.userId, userId: m.userId, name: m.displayName, active: m.active,
    todo: m.todo, inProgress: m.inProgress, overdue: m.overdue, total: m.todo + m.inProgress }))
  members.sort((a, b) => b.total - a.total || b.inProgress - a.inProgress || a.name.localeCompare(b.name, 'zh-CN'))
  const unassigned = props.workload?.unassigned
  return unassigned && unassigned.todo + unassigned.inProgress ? [...members, { key: '', userId: null, name: '未分配', active: true,
    todo: unassigned.todo, inProgress: unassigned.inProgress, overdue: unassigned.overdue, total: unassigned.todo + unassigned.inProgress }] : members
})
const busy = computed(() => rows.value.filter(row => row.total > 0))
const idle = computed(() => rows.value.filter(row => row.total === 0))
const visible = computed(() => showIdle.value ? [...busy.value, ...idle.value] : busy.value)
const max = computed(() => Math.max(1, ...rows.value.map(row => row.total)))
const totals = computed(() => busy.value.filter(row => row.userId).reduce((sum, row) => ({ inProgress: sum.inProgress + row.inProgress, todo: sum.todo + row.todo, overdue: sum.overdue + row.overdue }), { inProgress: 0, todo: 0, overdue: 0 }))

watch(() => props.workload, () => {
  expanded.value = new Set(busy.value.filter(row => expanded.value.has(row.key)).map(row => row.key))
  for (const key of expanded.value) emit('load', key, false)
})

function toggle(row: Row) {
  const next = new Set(expanded.value)
  if (next.delete(row.key)) { expanded.value = next; return }
  next.add(row.key); expanded.value = next
  if (!props.tasks.has(row.key)) emit('load', row.key, false)
}
function open(task: TeamMemberTask) {
  void router.push({ name: 'project-overview', params: { projectId: task.projectId }, query: { view: 'table', workItemId: task.id } })
}
const width = (value: number) => `${value / max.value * 100}%`
</script>

<template>
  <section
    class="team-panel"
    aria-labelledby="team-workload-title"
  >
    <header class="team-panel__header">
      <h2 id="team-workload-title">
        成员当前任务
      </h2>
      <div class="team-legend">
        <span><i class="team-legend__swatch team-legend__swatch--progress" />进行中 {{ totals.inProgress }}</span>
        <span><i class="team-legend__swatch team-legend__swatch--todo" />待开始 {{ totals.todo }}</span>
        <span class="team-overdue">逾期 {{ totals.overdue }}</span>
      </div>
    </header>
    <el-alert
      v-if="error"
      :title="workload ? `刷新失败，当前显示上次数据。${error}` : error"
      type="error"
      :closable="false"
    >
      <el-button
        text
        @click="emit('retry')"
      >
        重试
      </el-button>
    </el-alert>
    <div
      v-loading="loading"
      class="team-workload"
    >
      <template
        v-for="row in visible"
        :key="row.key"
      >
        <button
          type="button"
          class="team-workload__row"
          :aria-expanded="expanded.has(row.key)"
          :disabled="!row.total"
          @click="toggle(row)"
        >
          <el-icon
            class="team-workload__chevron"
            :class="{ open: expanded.has(row.key) }"
          >
            <ArrowRight />
          </el-icon>
          <span class="team-workload__name">
            <YpAssignee
              v-if="row.userId"
              :user-id="row.userId"
              :display-name="row.name"
              size="table"
              tooltip-disabled
            /><span v-else>未分配</span><small v-if="!row.active">非在职</small>
          </span>
          <span
            class="team-workload__bar"
            aria-hidden="true"
          >
            <i
              class="team-workload__segment team-workload__segment--progress"
              :style="{ width: width(row.inProgress) }"
            /><i
              class="team-workload__segment team-workload__segment--todo"
              :style="{ width: width(row.todo) }"
            />
          </span>
          <span class="team-workload__count">
            <strong>{{ row.total }}</strong>
            <small>进行中 {{ row.inProgress }} · 待开始 {{ row.todo }}</small>
            <small
              v-if="row.overdue"
              class="team-overdue"
            >逾期 {{ row.overdue }}</small>
          </span>
        </button>
        <div
          v-if="expanded.has(row.key)"
          class="team-workload__tasks"
        >
          <el-alert
            v-if="tasks.get(row.key)?.error"
            :title="tasks.get(row.key)!.error"
            type="error"
            :closable="false"
          />
          <el-table
            v-loading="tasks.get(row.key)?.loading && !tasks.get(row.key)?.items.length"
            :data="tasks.get(row.key)?.items ?? []"
            size="small"
            empty-text="暂无当前任务"
          >
            <el-table-column
              prop="itemNo"
              label="编号"
              width="120"
            />
            <el-table-column
              label="工作项"
              min-width="260"
            >
              <template #default="{ row: task }">
                <el-button
                  link
                  type="primary"
                  class="team-task-link"
                  @click="open(task as TeamMemberTask)"
                >
                  {{ task.title }}
                </el-button>
              </template>
            </el-table-column>
            <el-table-column
              prop="projectName"
              label="项目"
              min-width="160"
              show-overflow-tooltip
            />
            <el-table-column
              label="状态"
              width="140"
            >
              <template #default="{ row: task }">
                <span class="team-status"><i :style="{ background: workItemLabelColorValue(task.statusColor) }" />{{ task.statusName }}</span>
              </template>
            </el-table-column>
            <el-table-column
              label="优先级"
              width="100"
            >
              <template #default="{ row: task }">
                <span
                  v-if="task.priorityName"
                  class="team-status"
                ><i :style="{ background: workItemLabelColorValue(task.priorityColor ?? undefined) }" />{{ task.priorityName }}</span>
              </template>
            </el-table-column>
            <el-table-column
              label="截止日期"
              width="120"
            >
              <template #default="{ row: task }">
                <span :class="{ 'team-overdue': task.overdue }">{{ task.dueDate ? task.dueDate.toISOString().slice(0, 10) : '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
          <el-button
            v-if="(tasks.get(row.key)?.items.length ?? 0) < (tasks.get(row.key)?.total ?? 0)"
            text
            type="primary"
            :loading="!!tasks.get(row.key)?.loading"
            @click="emit('load', row.key, true)"
          >
            加载更多（{{ tasks.get(row.key)!.items.length }} / {{ tasks.get(row.key)!.total }}）
          </el-button>
        </div>
      </template>
      <p
        v-if="idle.length"
        class="team-panel__note"
      >
        另有 {{ idle.length }} 位成员当前无任务<el-button
          link
          type="primary"
          @click="showIdle = !showIdle"
        >
          {{ showIdle ? '收起' : '显示' }}
        </el-button>
      </p>
      <p
        v-if="workload && !rows.length"
        class="team-panel__note"
      >
        当前范围没有成员
      </p>
    </div>
  </section>
</template>
