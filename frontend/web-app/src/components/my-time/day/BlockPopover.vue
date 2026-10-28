<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElButton } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import { workItemLabelColorValue } from '../../projects/workItemLabelColors'
import { isMigratedCategory } from '../../timer/timeCategories'
import { companyDateTime } from '../../timer/timeFormat'
import ItemPicker from './ItemPicker.vue'
import TimeField from './TimeField.vue'
import { blockEnd, durationLabel, formatClock, type BlockShare, type DayBlock, type Range } from './dayModel'

const props = defineProps<{ block: DayBlock; share: BlockShare; bounds: Range; limit: number; date: string; timezone: string
  timerEnabled: boolean; busy: boolean }>()
/** reassign carries the target work item id or category key. */
const emit = defineEmits<{ save: [range: Range]; remove: []; reassign: [key: string]; exclusive: []; stop: []; close: [] }>()

const view = ref<'main' | 'reassign' | 'delete'>('main')
const start = ref(props.block.start)
const end = ref(blockEnd(props.block, props.limit))
const startValid = ref(true)
const endValid = ref(true)
watch(() => [props.block.id, props.block.etag], () => {
  start.value = props.block.start
  end.value = blockEnd(props.block, props.limit)
  startValid.value = true
  endValid.value = true
  view.value = 'main'
})
const running = computed(() => props.block.end === null)
const editable = computed(() => props.block.canEdit && !running.value)
const startInDay = computed(() => props.block.start >= props.bounds.start)
const endInDay = computed(() => blockEnd(props.block, props.limit) <= props.bounds.end)
const changed = computed(() => editable.value && (!startValid.value || !endValid.value || start.value !== props.block.start || end.value !== props.block.end))
const problem = computed(() => {
  if (!changed.value) return ''
  if (!startValid.value || !endValid.value) return '请输入有效的时间'
  if (end.value <= start.value) return '结束时间要晚于开始时间'
  if (end.value > props.limit) return '不能记录未来的时间'
  return ''
})
const outside = (ms: number) => companyDateTime(new Date(ms), props.timezone).slice(5, 16).replace('-', '/').replace('T', ' ')
const color = computed(() => workItemLabelColorValue(props.block.colorToken ?? undefined))
const link = computed(() => props.block.projectId && props.block.workItemId
  ? { name: 'project-overview', params: { projectId: props.block.projectId }, query: { workItemId: props.block.workItemId } } : undefined)

function save() { if (!problem.value) emit('save', { start: start.value, end: end.value }) }
</script>

<template>
  <div
    class="block-popover"
    role="dialog"
    :aria-label="`记录：${block.title}`"
    :style="{ '--block-popover-color': color }"
  >
    <header class="block-popover__head">
      <span class="block-popover__title">
        <router-link
          v-if="link"
          :to="link"
        >{{ block.title }}</router-link>
        <strong v-else>{{ block.title }}</strong>
        <small>{{ block.projectName ?? '' }}</small>
      </span>
      <el-button
        text
        circle
        size="small"
        :icon="Close"
        aria-label="关闭"
        @click="emit('close')"
      />
    </header>

    <div class="block-popover__times">
      <TimeField
        v-if="editable && startInDay"
        v-model="start"
        :date="date"
        :timezone="timezone"
        :day-start="bounds.start"
        :day-end="bounds.end"
        label="开始时间"
        immediate
        @validity="startValid = $event"
      />
      <span
        v-else
        class="block-popover__fixed"
      >{{ startInDay ? formatClock(block.start, timezone, bounds.end) : outside(block.start) }}</span>
      <span aria-hidden="true">–</span>
      <span
        v-if="running"
        class="block-popover__fixed"
      >计时中</span>
      <TimeField
        v-else-if="editable && endInDay"
        v-model="end"
        :date="date"
        :timezone="timezone"
        :day-start="bounds.start"
        :day-end="bounds.end"
        label="结束时间"
        immediate
        @validity="endValid = $event"
      />
      <span
        v-else
        class="block-popover__fixed"
      >{{ endInDay ? formatClock(end, timezone, bounds.end) : outside(end) }}</span>
      <span class="block-popover__duration">{{ durationLabel(end - start) }}</span>
    </div>
    <p
      v-if="problem"
      class="block-popover__problem"
      role="alert"
    >
      {{ problem }}
    </p>
    <p
      v-if="share.maxParallel > 1"
      class="block-popover__note"
    >
      与其他 {{ share.maxParallel - 1 }} 项并行 · 当天计入 {{ durationLabel(share.allocatedMs) }}
    </p>
    <p
      v-if="running"
      class="block-popover__note"
    >
      正在计时，停止后才能调整时段或归属。
    </p>
    <p
      v-else-if="isMigratedCategory(block.timeCategory)"
      class="block-popover__note"
    >
      迁入工时由管理员按日迁入，排出的时段不代表实际工作时间，成员不能修改。
    </p>
    <p
      v-else-if="!block.canEdit"
      class="block-popover__note"
    >
      这条记录所属的项目已归档、工作项已删除，或你已没有写权限，不能修改。
    </p>

    <ItemPicker
      v-if="view === 'reassign'"
      :exclude="[block.subjectKey]"
      placeholder="改为记录到哪个工作项或分类？"
      @pick="emit('reassign', $event.key)"
      @escape="view = 'main'"
    />

    <footer
      v-if="view === 'delete'"
      class="block-popover__foot is-confirm"
    >
      <span>删除这条 {{ durationLabel(end - start) }} 的记录？</span>
      <el-button
        size="small"
        @click="view = 'main'"
      >
        取消
      </el-button>
      <el-button
        type="danger"
        size="small"
        :loading="busy"
        @click="emit('remove')"
      >
        删除
      </el-button>
    </footer>
    <footer
      v-else-if="view === 'reassign'"
      class="block-popover__foot"
    >
      <el-button
        text
        size="small"
        @click="view = 'main'"
      >
        返回
      </el-button>
    </footer>
    <footer
      v-else
      class="block-popover__foot"
    >
      <el-button
        v-if="changed"
        type="primary"
        size="small"
        :loading="busy"
        :disabled="!!problem"
        @click="save"
      >
        保存时段
      </el-button>
      <el-button
        v-if="editable"
        size="small"
        @click="view = 'reassign'"
      >
        更换工作项
      </el-button>
      <el-button
        v-if="editable && share.maxParallel > 1"
        size="small"
        :loading="busy"
        @click="emit('exclusive')"
      >
        独占此时段
      </el-button>
      <el-button
        v-if="running && timerEnabled"
        size="small"
        :loading="busy"
        @click="emit('stop')"
      >
        停止计时
      </el-button>
      <el-button
        v-if="editable"
        text
        type="danger"
        size="small"
        class="block-popover__delete"
        @click="view = 'delete'"
      >
        删除
      </el-button>
    </footer>
  </div>
</template>

<style scoped>
.block-popover{display:grid;gap:10px}
.block-popover__head{display:flex;align-items:flex-start;gap:8px;padding-left:10px;border-left:3px solid var(--block-popover-color)}
.block-popover__title{display:grid;flex:1;min-width:0;gap:2px}
.block-popover__title a,.block-popover__title strong{overflow:hidden;color:var(--yp-text-primary);font-size:14px;font-weight:600;text-decoration:none;white-space:nowrap;text-overflow:ellipsis}
.block-popover__title a:hover{color:var(--yp-action-primary);text-decoration:underline}
.block-popover__title small{overflow:hidden;color:var(--yp-text-muted);font-size:12px;white-space:nowrap;text-overflow:ellipsis}
.block-popover__times{display:flex;align-items:center;gap:6px;color:var(--yp-text-secondary)}
.block-popover__fixed{color:var(--yp-text-primary);font:13px var(--yp-font-mono);font-variant-numeric:tabular-nums}
.block-popover__duration{margin-left:auto;color:var(--yp-text-primary);font:600 13px var(--yp-font-mono);font-variant-numeric:tabular-nums}
.block-popover__problem{margin:0;color:var(--yp-status-red);font-size:12px}
.block-popover__note{margin:0;padding:6px 8px;border-radius:var(--yp-radius-sm);background:var(--yp-bg-sunken);color:var(--yp-text-secondary);font-size:12px;line-height:18px}
.block-popover__foot{display:flex;flex-wrap:wrap;align-items:center;gap:6px}
.block-popover__foot .el-button+.el-button{margin-left:0}
.block-popover__foot.is-confirm span{flex:1;color:var(--yp-text-primary);font-size:13px}
.block-popover__delete{margin-left:auto}
</style>
