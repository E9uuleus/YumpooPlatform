<script setup lang="ts">
import type { ProjectMember } from '@yumpoo/api-client'
import { ElTabPane as ElTabPaneRaw, ElTabs as ElTabsRaw } from 'element-plus'
import { computed, ref, type DefineComponent } from 'vue'
import WorkItemDiscussion from './WorkItemDiscussion.vue'

interface DiscussionHandle {
  hasDraft: boolean
  discardDraft: () => void
}

const props = defineProps<{
  modelValue: 'details' | 'discussion'
  workItemId: string
  members: ProjectMember[]
  canPublish: boolean
  readOnlyReason?: string | undefined
  beforeLeave?: ((next: string | number, previous: string | number) => boolean | Promise<boolean>) | undefined
}>()
const emit = defineEmits<{ 'update:modelValue': [value: 'details' | 'discussion'] }>()
const ElTabs = ElTabsRaw as unknown as DefineComponent
const ElTabPane = ElTabPaneRaw as unknown as DefineComponent
const discussion = ref<DiscussionHandle>()
const tab = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value as 'details' | 'discussion'),
})
const hasDraft = computed(() => Boolean(discussion.value?.hasDraft))

function discardDraft(): void {
  discussion.value?.discardDraft()
}

defineExpose({ hasDraft, discardDraft })
</script>

<template>
  <el-tabs
    v-model="tab"
    :before-leave="beforeLeave"
    class="detail-tabs"
  >
    <el-tab-pane
      label="详情"
      name="details"
    >
      <slot name="details" />
    </el-tab-pane>
    <el-tab-pane
      label="协作讨论"
      name="discussion"
      lazy
    >
      <work-item-discussion
        v-if="tab === 'discussion'"
        ref="discussion"
        :work-item-id="workItemId"
        :members="members"
        :can-publish="canPublish"
        :read-only-reason="readOnlyReason"
      />
    </el-tab-pane>
  </el-tabs>
</template>
