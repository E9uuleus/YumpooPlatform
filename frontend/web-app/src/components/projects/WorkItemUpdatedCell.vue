<script setup lang="ts">
import { ElTooltip } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useSession } from '../../composables/useSession'
import { formatChineseTimestamp, formatRelativeTime } from '../../design-system/dates'
import YpAssignee from '../yp/YpAssignee.vue'

const props = defineProps<{
  item: { updatedAt: Date; updatedByUserId?: string | undefined; updatedByDisplayName?: string | undefined }
}>()
const emit = defineEmits<{ openActivity: [] }>()
const session = useSession()
const now = ref(new Date())
const memberName = computed(() => props.item.updatedByDisplayName?.trim() || '历史成员')
const tooltip = computed(() => `由${memberName.value}更新于${formatChineseTimestamp(props.item.updatedAt, session.authentication.value?.company.timezone ?? 'Asia/Shanghai')}`)
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { timer = setInterval(() => { now.value = new Date() }, 60_000) })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <el-tooltip
    :content="tooltip"
    placement="top"
  >
    <button
      class="work-item-updated-cell"
      type="button"
      :aria-label="tooltip"
      @click.stop="emit('openActivity')"
    >
      <yp-assignee
        :user-id="item.updatedByUserId"
        :display-name="memberName"
        size="table"
        :show-name="false"
        :tooltip-disabled="true"
      />
      <time :datetime="item.updatedAt.toISOString()">
        {{ formatRelativeTime(item.updatedAt, now) }}
      </time>
    </button>
  </el-tooltip>
</template>

<style scoped>
.work-item-updated-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 100%;
  padding: 0 var(--work-item-updated-cell-padding, 12px);
  box-sizing: border-box;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: var(--yp-text-secondary);
  font: inherit;
  white-space: nowrap;
  cursor: pointer;
}
.work-item-updated-cell > :first-child { flex: 0 0 auto; }
.work-item-updated-cell time { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; text-align: center; }
.work-item-updated-cell:hover { background: var(--yp-bg-sunken); }
.work-item-updated-cell:active { background: var(--yp-bg-selected); }
.work-item-updated-cell:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: -2px; }
</style>
