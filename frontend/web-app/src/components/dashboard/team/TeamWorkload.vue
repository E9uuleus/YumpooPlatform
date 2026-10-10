<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElIcon } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import type { TeamMemberTask, TeamWorkload } from '@yumpoo/api-client'
import YpAssignee from '../../yp/YpAssignee.vue'
import { assigneeGroupColor } from '../../projects/workItemGrouping'
import { workItemLabelColorValue } from '../../projects/workItemLabelColors'
import '../../projects/workItemAccentBar.css'
import type { MemberTasks } from './useTeamDashboard'

const props = defineProps<{ workload?: TeamWorkload | undefined; loading: boolean; error: string; tasks: Map<string, MemberTasks> }>()
const emit = defineEmits<{ retry: []; load: [key: string, append: boolean] }>()
const router = useRouter()
const expanded = ref(new Set<string>()), showIdle = ref(false)
interface Row { key: string; userId: string | null; name: string; active: boolean; color: string; todo: number; inProgress: number; overdue: number; total: number }
const rows = computed<Row[]>(() => {
  const source = props.workload?.members ?? [], assigned: Record<string, string> = {}
  // Colour in id order so a member keeps the same accent when the workload ranking changes.
  source.map(member => member.userId).sort().forEach(id => assigneeGroupColor(id, assigned))
  const members = source.map(m => ({ key: m.userId, userId: m.userId, name: m.displayName, active: m.active, color: assigneeGroupColor(m.userId, assigned),
    todo: m.todo, inProgress: m.inProgress, overdue: m.overdue, total: m.todo + m.inProgress }))
  members.sort((a, b) => b.total - a.total || b.inProgress - a.inProgress || a.name.localeCompare(b.name, 'zh-CN'))
  const unassigned = props.workload?.unassigned
  return unassigned && unassigned.todo + unassigned.inProgress ? [...members, { key: '', userId: null, name: '未分配', active: true, color: workItemLabelColorValue(),
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
const labelStyle = (token: string | null | undefined) => ({ backgroundColor: workItemLabelColorValue(token ?? undefined), color: 'var(--yp-text-inverse)' })
</script>

<template>
  <section
    class="team-view"
    aria-label="成员当前任务"
  >
    <div class="dashboard-toolbar team-toolbar">
      <slot name="switch" />
      <div class="team-toolbar__end team-legend">
        <span><i class="team-legend__swatch team-legend__swatch--progress" />进行中 <b>{{ totals.inProgress }}</b></span>
        <span><i class="team-legend__swatch team-legend__swatch--todo" />待开始 <b>{{ totals.todo }}</b></span>
        <span class="team-overdue">逾期 <b>{{ totals.overdue }}</b></span>
      </div>
    </div>
    <div
      v-loading="loading"
      class="team-body"
    >
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
      <div class="team-groups">
        <div
          v-for="row in visible"
          :key="row.key"
          class="team-group"
          :style="{ '--team-group-accent': row.color }"
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
            <YpAssignee
              v-if="row.userId"
              :user-id="row.userId"
              :display-name="row.name"
              size="table"
              :show-name="false"
              tooltip-disabled
            />
            <span class="team-group__name">{{ row.name }}</span>
            <small v-if="!row.active">非在职</small>
            <small>{{ row.total }} 个工作项</small>
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
            <small>进行中 {{ row.inProgress }} · 待开始 {{ row.todo }}</small>
            <small
              v-if="row.overdue"
              class="team-overdue"
            >逾期 {{ row.overdue }}</small>
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
            <div
              v-loading="tasks.get(row.key)?.loading && !tasks.get(row.key)?.items.length"
              class="team-tasks-wrap work-item-accent-bar"
            >
              <table class="team-tasks">
                <colgroup>
                  <col>
                  <col class="team-tasks__project">
                  <col class="team-tasks__label">
                  <col class="team-tasks__label">
                  <col class="team-tasks__due">
                </colgroup>
                <thead>
                  <tr>
                    <th>工作项</th>
                    <th>项目</th>
                    <th class="team-task__label">
                      状态
                    </th>
                    <th class="team-task__label">
                      优先级
                    </th>
                    <th>截止日期</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="task in tasks.get(row.key)?.items ?? []"
                    :key="task.id"
                  >
                    <td>
                      <span class="team-task__title"><span class="team-task__no">{{ task.itemNo }}</span><button
                        type="button"
                        class="team-task-link"
                        @click="open(task)"
                      >{{ task.title }}</button></span>
                    </td>
                    <td :title="task.projectName">
                      {{ task.projectName }}
                    </td>
                    <td class="team-task__label">
                      <span :style="labelStyle(task.statusColor)">{{ task.statusName }}</span>
                    </td>
                    <td class="team-task__label">
                      <span
                        v-if="task.priorityName"
                        :style="labelStyle(task.priorityColor)"
                      >{{ task.priorityName }}</span>
                    </td>
                    <td
                      class="team-task__due"
                      :class="{ 'team-overdue': task.overdue }"
                    >
                      {{ task.dueDate ? task.dueDate.toISOString().slice(0, 10) : '—' }}
                    </td>
                  </tr>
                  <tr v-if="tasks.get(row.key)?.loading === false && !tasks.get(row.key)?.items.length && !tasks.get(row.key)?.error">
                    <td
                      colspan="5"
                      class="team-empty"
                    >
                      暂无当前任务
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
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
        </div>
        <p
          v-if="idle.length"
          class="team-note"
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
          class="team-note"
        >
          当前没有成员
        </p>
      </div>
    </div>
  </section>
</template>
