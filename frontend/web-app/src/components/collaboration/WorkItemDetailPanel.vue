<script setup lang="ts">
import type { ProjectMember, WorkItemDetail } from '@yumpoo/api-client'
import { ElMessage, ElMessageBox, ElTabPane as ElTabPaneRaw, ElTabs as ElTabsRaw } from 'element-plus'
import { computed, ref, type DefineComponent } from 'vue'
import WorkItemDescription from './WorkItemDescription.vue'
import WorkItemDiscussion from './WorkItemDiscussion.vue'
import WorkItemCellActivityLog from './WorkItemCellActivityLog.vue'
import WorkItemRelations from './WorkItemRelations.vue'

interface DraftHandle {
  hasDraft: boolean
  busy: boolean
  discardDraft: () => void
}

const props = defineProps<{
  modelValue: 'details' | 'discussion' | 'relations' | 'activity'
  detail: WorkItemDetail
  members: ProjectMember[]
  canPublish: boolean
  readOnlyReason?: string | undefined
}>()
const emit = defineEmits<{
  'update:modelValue': [value: 'details' | 'discussion' | 'relations' | 'activity']
  'relationsChanged': [affectedWorkItemIds: string[]]
  'discussionChanged': [workItemId: string]
  'descriptionUpdated': [detail: WorkItemDetail]
  'openWorkItem': [target: { workItemId: string, projectId: string }]
}>()
const ElTabs = ElTabsRaw as unknown as DefineComponent
const ElTabPane = ElTabPaneRaw as unknown as DefineComponent
const description = ref<DraftHandle>()
const discussion = ref<DraftHandle>()
const tab = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value as 'details' | 'discussion' | 'relations' | 'activity'),
})
const hasDraft = computed(() => Boolean(description.value?.hasDraft || discussion.value?.hasDraft))
const busy = computed(() => Boolean(description.value?.busy || discussion.value?.busy))

function discardDraft(): void {
  description.value?.discardDraft()
  discussion.value?.discardDraft()
}

// Only the discussion pane unmounts on tab switch; the description stays mounted and keeps its draft.
async function beforeTabLeave(_next: string | number, previous: string | number): Promise<boolean> {
  if (previous !== 'discussion' || !discussion.value) return true
  if (discussion.value.busy) { ElMessage.info('讨论正在保存，请稍候再离开。'); return false }
  if (!discussion.value.hasDraft) return true
  try {
    await ElMessageBox.confirm('离开将丢弃尚未发布或保存的讨论草稿。', '放弃讨论草稿', {
      confirmButtonText: '放弃草稿', cancelButtonText: '继续编写', type: 'warning',
    })
  } catch { return false }
  discussion.value.discardDraft()
  return true
}

defineExpose({ hasDraft, busy, discardDraft })
</script>

<template>
  <el-tabs
    v-model="tab"
    :before-leave="beforeTabLeave"
    class="detail-tabs"
  >
    <el-tab-pane
      label="详情"
      name="details"
    >
      <work-item-description
        ref="description"
        :work-item-id="detail.id"
        :description="detail.description"
        :etag="detail.etag"
        :can-edit="detail.capabilities.canEditFields"
        @updated="emit('descriptionUpdated', $event)"
      />
    </el-tab-pane>
    <el-tab-pane
      label="协作讨论"
      name="discussion"
      lazy
    >
      <work-item-discussion
        v-if="tab === 'discussion'"
        ref="discussion"
        :work-item-id="detail.id"
        :members="members"
        :can-publish="canPublish"
        :read-only-reason="readOnlyReason"
        @changed="emit('discussionChanged', $event)"
      />
    </el-tab-pane>
    <el-tab-pane
      label="关系"
      name="relations"
      lazy
    >
      <work-item-relations
        v-if="tab === 'relations'"
        :work-item-id="detail.id"
        :current-project-id="detail.projectId"
        @changed="emit('relationsChanged', $event)"
        @open-work-item="emit('openWorkItem', $event)"
      />
    </el-tab-pane>
    <el-tab-pane
      label="动态"
      name="activity"
      lazy
    >
      <work-item-cell-activity-log
        v-if="tab === 'activity'"
        :work-item-id="detail.id"
      />
    </el-tab-pane>
  </el-tabs>
</template>
