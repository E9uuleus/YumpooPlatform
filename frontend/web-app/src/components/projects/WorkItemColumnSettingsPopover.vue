<script setup lang="ts">
import type { ProjectContentCatalog, WorkItemLabelCatalog } from '@yumpoo/api-client'
import { ElPopover } from 'element-plus'
import { computed, ref } from 'vue'
import WorkItemContentPopoverContent from './WorkItemContentPopoverContent.vue'
import WorkItemLabelPopoverContent from './WorkItemLabelPopoverContent.vue'

const props = defineProps<{
  kind: 'status' | 'priority' | 'content' | undefined
  anchor: HTMLElement | undefined
  projectId: string
  labelCatalog?: WorkItemLabelCatalog | undefined
  contentCatalog?: ProjectContentCatalog | undefined
}>()
const emit = defineEmits<{
  close: []
  labelsUpdated: [catalog: WorkItemLabelCatalog]
  contentsUpdated: [catalog: ProjectContentCatalog]
}>()
const busy = ref(false)
const editor = ref<HTMLElement>()
const title = computed(() => ({ status: '状态列设置', priority: '优先级列设置', content: '工作项类别列设置' })[props.kind ?? 'status'])
function requestClose(): void { if (!busy.value) emit('close') }
function focusEditor(): void { editor.value?.querySelector('input')?.focus({ preventScroll: true }) }
</script>

<template>
  <el-popover
    v-if="kind && anchor"
    :key="kind"
    :visible="true"
    virtual-triggering
    :virtual-ref="anchor"
    trigger="click"
    placement="bottom"
    width="auto"
    :persistent="false"
    popper-class="work-items-label-popover work-item-view-control"
    @show="focusEditor"
    @update:visible="value => !value && requestClose()"
  >
    <section
      ref="editor"
      :aria-label="title"
      @keydown.esc.stop.prevent="requestClose"
    >
      <work-item-label-popover-content
        v-if="kind === 'status' || kind === 'priority'"
        :kind="kind"
        :project-id="projectId"
        :catalog="labelCatalog"
        :can-manage="true"
        initial-mode="edit"
        @busy-change="busy = $event"
        @updated="emit('labelsUpdated', $event)"
        @done="emit('close')"
      />
      <work-item-content-popover-content
        v-else-if="kind === 'content'"
        :project-id="projectId"
        :catalog="contentCatalog"
        :can-manage="true"
        initial-mode="edit"
        @busy-change="busy = $event"
        @updated="emit('contentsUpdated', $event)"
        @done="emit('close')"
      />
    </section>
  </el-popover>
</template>
