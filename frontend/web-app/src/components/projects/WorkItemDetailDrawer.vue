<script setup lang="ts">
import type { ProjectMember, WorkItemDetail } from '@yumpoo/api-client'
import { ElButton, ElDrawer } from 'element-plus'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { vBrandLoading as vLoading } from '../../brand/loading'
import WorkItemDetailPanel from '../collaboration/WorkItemDetailPanel.vue'
import YpNotice from '../yp/YpNotice.vue'

const props = defineProps<{
  modelValue: boolean
  detailLoading: boolean
  detail: WorkItemDetail | undefined
  embedded: boolean
  activeMembers: ProjectMember[]
  canPublishDiscussion: boolean
  discussionReadOnlyReason: string | undefined
  restoringArchive: boolean
  contentName: (id: string) => string
  beforeDetailClose: (done: () => void) => Promise<void>
}>()
const detailTab = defineModel<'details' | 'discussion' | 'activity'>('detailTab', { required: true })
const emit = defineEmits<{
  close: []
  'update:modelValue': [value: boolean]
  resizeEnd: []
  restore: []
  discussionChanged: [workItemId: string]
  descriptionUpdated: [detail: WorkItemDetail]
}>()
const detailPanel = ref<InstanceType<typeof WorkItemDetailPanel>>()
defineExpose({ detailPanel })

const DRAWER_MIN_WIDTH = 480
const DRAWER_VIEWPORT_GUTTER = 60
const drawerWidth = ref(560)
const isResizingDrawer = ref(false)
function onDrawerResizePointerDown(event: PointerEvent): void {
  event.preventDefault()
  event.stopPropagation()
  isResizingDrawer.value = true
  const startX = event.clientX
  const startWidth = drawerWidth.value

  const onPointerMove = (e: PointerEvent) => {
    const delta = startX - e.clientX
    const maxWidth = Math.max(DRAWER_MIN_WIDTH, window.innerWidth - DRAWER_VIEWPORT_GUTTER)
    const nextWidth = Math.max(DRAWER_MIN_WIDTH, Math.min(maxWidth, startWidth + delta))
    if (nextWidth === drawerWidth.value) return
    drawerWidth.value = nextWidth
    if (!props.embedded) {
      document.body.style.setProperty('--yp-work-items-drawer-width', `${nextWidth}px`)
      document.body.style.setProperty('--yp-work-items-drawer-inset', `${nextWidth}px`)
    }
  }

  const onPointerUp = () => {
    isResizingDrawer.value = false
    emit('resizeEnd')
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', onPointerUp)
  }

  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerUp)
}

function syncDrawerInset(): void {
  if (props.embedded) return
  document.body.classList.toggle('yp-work-items-drawer-open', props.modelValue)
  if (props.modelValue) {
    document.body.style.setProperty('--yp-work-items-drawer-width', `${drawerWidth.value}px`)
  }
  document.body.style.setProperty('--yp-work-items-drawer-inset', props.modelValue ? `${drawerWidth.value}px` : '0px')
}
watch(() => props.modelValue, syncDrawerInset, { flush: 'post' })
watch(isResizingDrawer, value => { if (!props.embedded) document.body.classList.toggle('yp-work-items-drawer-resizing', value) }, { flush: 'sync' })
onMounted(syncDrawerInset)
onBeforeUnmount(() => {
  if (!props.embedded) {
    document.body.classList.remove('yp-work-items-drawer-open', 'yp-work-items-drawer-resizing')
    document.body.style.removeProperty('--yp-work-items-drawer-width')
    document.body.style.removeProperty('--yp-work-items-drawer-inset')
  }
})
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :before-close="beforeDetailClose"
    :modal="false"
    :modal-penetrable="true"
    append-to-body
    title="工作项详情"
    header-class="work-items-detail-drawer__header"
    modal-class="work-items-drawer-overlay"
    :class="['work-items-detail-drawer', { 'work-items-detail-drawer--resizing': isResizingDrawer }]"
    :size="`${drawerWidth}px`"
    @close="emit('close')"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div
      class="drawer-resize-handle"
      :class="{ 'drawer-resize-handle--resizing': isResizingDrawer }"
      title="拖动调整抽屉宽度"
      @pointerdown="onDrawerResizePointerDown"
    >
      <div class="drawer-resize-grip" aria-hidden="true">
        <svg width="6" height="18" viewBox="0 0 6 18" fill="currentColor">
          <circle cx="1.5" cy="2" r="1.1" />
          <circle cx="4.5" cy="2" r="1.1" />
          <circle cx="1.5" cy="6.5" r="1.1" />
          <circle cx="4.5" cy="6.5" r="1.1" />
          <circle cx="1.5" cy="11" r="1.1" />
          <circle cx="4.5" cy="11" r="1.1" />
          <circle cx="1.5" cy="15.5" r="1.1" />
          <circle cx="4.5" cy="15.5" r="1.1" />
        </svg>
      </div>
    </div>
    <div
      v-loading="detailLoading"
      class="detail-panel"
    >
      <template v-if="detail">
        <yp-notice
          v-if="detail.archived"
          class="work-item-archive-notice"
          tone="warning"
        >
          此工作项已归档
          <template #actions>
            <el-button
              v-if="detail.capabilities.canDelete"
              :loading="restoringArchive"
              text
              @click="emit('restore')"
            >
              恢复工作项
            </el-button>
          </template>
        </yp-notice>
        <div class="detail-heading">
          <small>{{ contentName(detail.contentId) }} · {{ detail.itemNo }}</small>
          <h2>{{ detail.title }}</h2>
        </div>
        <work-item-detail-panel
          ref="detailPanel"
          v-model="detailTab"
          :detail="detail"
          :members="activeMembers"
          :can-publish="canPublishDiscussion"
          :read-only-reason="discussionReadOnlyReason"
          @discussion-changed="emit('discussionChanged', $event)"
          @description-updated="emit('descriptionUpdated', $event)"
        />
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.detail-heading h2 {
  margin: var(--yp-space-1) 0 var(--yp-space-4);
}

.work-item-archive-notice { margin-bottom: 16px; }

.detail-heading small {
  color: var(--yp-text-muted);
}
</style>

<style>
/* 抽屉无蒙版交互穿透 */
.work-items-drawer-overlay {
  --el-transition-duration: var(--yp-work-items-drawer-duration, 300ms);
  pointer-events: none !important;
  background: transparent !important;
}

.work-items-drawer-overlay .el-drawer,
.work-items-detail-drawer {
  transform: translateX(calc(var(--yp-work-items-drawer-width, 560px) - var(--yp-work-items-drawer-inset, 0px))) !important;
  transition: none !important;
  pointer-events: auto !important;
  box-shadow: -4px 0 24px color-mix(in srgb, var(--yp-text-primary) 12%, transparent) !important;
  overflow: visible !important;
}

.work-items-detail-drawer--resizing {
  transition: none !important;
}

/* 加载时详情为空：面板撑满抽屉内容区，品牌加载遮罩才能完整居中，不被抽屉滚动区裁掉。 */
.work-items-detail-drawer .detail-panel {
  min-height: 100%;
}

/* 抽屉左侧拖动手柄 */
.drawer-resize-handle {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 20px;
  cursor: ew-resize;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  user-select: none;
  touch-action: none;
}

.drawer-resize-handle::before {
  content: '';
  position: absolute;
  inset: 0 10px 0 0;
  background: var(--yp-bg-sunken);
  box-shadow: -2px 0 8px color-mix(in srgb, var(--yp-text-primary) 10%, transparent);
  opacity: 0;
  transition: opacity var(--yp-motion-fast) var(--yp-ease-standard);
}

.drawer-resize-grip {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 12px;
  height: 32px;
  border-radius: 4px;
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
  border: 1px solid var(--yp-monday-grid-border);
  box-shadow: 0 1px 4px color-mix(in srgb, var(--yp-text-primary) 10%, transparent);
  opacity: 0;
  transform: scale(.92);
  transition: opacity var(--yp-motion-fast) var(--yp-ease-standard),
              transform var(--yp-motion-fast) var(--yp-ease-standard);
}

.drawer-resize-handle:hover::before,
.drawer-resize-handle--resizing::before,
.drawer-resize-handle:hover .drawer-resize-grip,
.drawer-resize-handle--resizing .drawer-resize-grip {
  opacity: 1;
}

.drawer-resize-handle:hover .drawer-resize-grip,
.drawer-resize-handle--resizing .drawer-resize-grip {
  transform: scale(1);
}
</style>
