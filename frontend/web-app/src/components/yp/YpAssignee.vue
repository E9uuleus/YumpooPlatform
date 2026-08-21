<script setup lang="ts">
import { ElAvatar } from 'element-plus'
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  userId?: string | null | undefined
  displayName?: string | null | undefined
  avatarUrl?: string | null | undefined
  accountStatus?: string | null | undefined
  employmentStatus?: string | null | undefined
  size?: 'table' | 'default' | 'detail'
  showName?: boolean
}>(), {
  userId: null,
  displayName: null,
  avatarUrl: null,
  accountStatus: null,
  employmentStatus: null,
  size: 'default',
  showName: true,
})

const palette = [
  'var(--yp-status-blue)',
  'var(--yp-status-purple)',
  'var(--yp-status-teal)',
  'var(--yp-status-pink)',
  'var(--yp-status-orange)',
  'var(--yp-status-gray)',
]

const avatarSize = computed(() => ({ table: 24, default: 32, detail: 40 })[props.size])
const name = computed(() => props.displayName?.trim() || '未分配')
const initials = computed(() => {
  if (!props.displayName?.trim()) return '—'
  const compact = props.displayName.trim().replace(/\s+/g, '')
  return compact.slice(0, Math.min(2, compact.length))
})
const avatarColor = computed(() => {
  const source = props.userId || props.displayName || 'unassigned'
  let hash = 0
  for (const character of source) hash = ((hash << 5) - hash + character.charCodeAt(0)) | 0
  return palette[Math.abs(hash) % palette.length]
})
const stateLabel = computed(() => {
  if (props.employmentStatus === 'LEFT') return '已离职'
  if (props.accountStatus === 'DISABLED') return '已停用'
  return undefined
})
</script>

<template>
  <span
    class="yp-assignee"
    :class="{ 'yp-assignee--unassigned': !userId && !displayName }"
  >
    <el-avatar
      :size="avatarSize"
      :src="avatarUrl ?? ''"
      :style="`--yp-assignee-color: ${avatarColor}`"
    >
      {{ initials }}
    </el-avatar>
    <span
      v-if="showName"
      class="yp-assignee__name"
    >
      {{ name }}
      <small v-if="stateLabel">（{{ stateLabel }}）</small>
    </span>
  </span>
</template>

<style scoped>
.yp-assignee {
  display: inline-flex;
  align-items: center;
  gap: var(--yp-space-2);
  min-width: 0;
  color: var(--yp-text-primary);
}

.yp-assignee :deep(.el-avatar) {
  flex: 0 0 auto;
  color: var(--yp-status-blue-foreground);
  background: var(--yp-assignee-color);
  font-size: var(--yp-type-caption-size);
  font-weight: 700;
}

.yp-assignee--unassigned :deep(.el-avatar) {
  color: var(--yp-text-muted);
  border: 1px dashed var(--yp-border-strong);
  background: transparent;
}

.yp-assignee__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.yp-assignee__name small {
  color: var(--yp-text-muted);
}
</style>
