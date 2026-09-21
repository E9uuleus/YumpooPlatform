<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDrawer, ElMessage, ElMessageBox, ElSkeleton } from 'element-plus'
import { AttachmentOwnerType, type ProjectMember, type WorkItemDetail } from '@yumpoo/api-client'
import { workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import WorkItemDetailPanel from '../collaboration/WorkItemDetailPanel.vue'
import LazyAttachmentPanel from '../collaboration/LazyAttachmentPanel.vue'
import YpAssignee from '../yp/YpAssignee.vue'

const props = defineProps<{ itemId: string; members: ProjectMember[]; initialTab?: 'details' | 'discussion' | 'activity' }>()
const emit = defineEmits<{ close: []; changed: []; openRelated: [id: string, projectId: string] }>()
const item = ref<WorkItemDetail>(), loading = ref(false), error = ref('')
const tab = ref<'details' | 'discussion' | 'relations' | 'activity'>(props.initialTab || 'details')
const panel = ref<InstanceType<typeof WorkItemDetailPanel>>()
let generation = 0
async function load() {
  const token = ++generation; loading.value = true; error.value = ''; item.value = undefined
  try { const value = await workItemsApi.getWorkItem({ workItemId: props.itemId }); if (token === generation) item.value = value }
  catch (reason) { const p = await toApiProblem(reason); if (token === generation) error.value = problemMessage(p) }
  finally { if (token === generation) loading.value = false }
}
async function canClose() {
  if (panel.value?.busy) { ElMessage.info('讨论正在保存，请稍候再离开。'); return false }
  if (!panel.value?.hasDraft) return true
  try {
    await ElMessageBox.confirm('离开将丢弃尚未发布或保存的讨论草稿。', '放弃讨论草稿', { confirmButtonText: '放弃草稿', cancelButtonText: '继续编写', type: 'warning' })
    panel.value?.discardDraft(); return true
  } catch { return false }
}
async function close(done?: () => void) { if (await canClose()) { done?.(); emit('close') } }
async function openRelated(target: { workItemId: string; projectId: string }) {
  if (await canClose()) emit('openRelated', target.workItemId, target.projectId)
}
const date = (value?: Date | null) => value ? new Date(value).toLocaleString('zh-CN') : '—'
watch(() => props.itemId, () => { tab.value = props.initialTab || 'details'; void load() }, { immediate: true })
onBeforeUnmount(() => { ++generation })
defineExpose({ canClose })
</script>
<template>
  <el-drawer
    :model-value="true"
    append-to-body
    title="工作项详情"
    size="min(600px, 100vw)"
    :before-close="close"
    class="work-item-shared-details"
    @closed="emit('close')"
  >
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    >
      <el-button
        text
        @click="load"
      >
        重试
      </el-button>
    </el-alert>
    <el-skeleton
      v-if="loading"
      :rows="8"
      animated
    />
    <template v-else-if="item">
      <small>{{ item.contentName }} · {{ item.itemNo }}</small><h2>{{ item.title }}</h2>
      <el-alert
        v-if="item.archived"
        title="此工作项已归档"
        :closable="false"
      />
      <WorkItemDetailPanel
        ref="panel"
        v-model="tab"
        :work-item-id="item.id"
        :current-project-id="item.projectId"
        :members="members"
        :can-publish="item.capabilities.canDiscuss"
        :before-leave="canClose"
        @discussion-changed="emit('changed')"
        @relations-changed="emit('changed')"
        @open-work-item="openRelated"
      >
        <template #details>
          <dl class="work-item-details-fields">
            <div><dt>工作项类型</dt><dd>{{ item.contentName }}</dd></div>
            <div>
              <dt>处理人</dt><dd>
                <YpAssignee
                  :user-id="item.assigneeUserId"
                  :display-name="item.assigneeDisplayName"
                />
              </dd>
            </div>
            <div><dt>报告人</dt><dd>{{ item.reporterDisplayName || '—' }}</dd></div>
            <div><dt>截止日期</dt><dd>{{ item.dueDate ? new Date(item.dueDate).toLocaleDateString('zh-CN') : '—' }} {{ item.dueTime }}</dd></div>
            <div><dt>计划时间</dt><dd>{{ item.timelineStartDate ? new Date(item.timelineStartDate).toLocaleDateString('zh-CN') : '—' }} — {{ item.timelineEndDate ? new Date(item.timelineEndDate).toLocaleDateString('zh-CN') : '—' }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ date(item.updatedAt) }}</dd></div>
          </dl>
          <p
            v-if="item.description"
            class="work-item-details-copy"
          >
            {{ item.description }}
          </p>
          <p
            v-if="item.notes"
            class="work-item-details-copy"
          >
            {{ item.notes }}
          </p>
          <LazyAttachmentPanel
            :owner-type="AttachmentOwnerType.WorkItem"
            :owner-id="item.id"
            :can-upload="item.capabilities.canEditFields"
          />
        </template>
      </WorkItemDetailPanel>
    </template>
  </el-drawer>
</template>
<style scoped>
.work-item-details-fields{display:grid;gap:16px}.work-item-details-fields>div{display:grid;grid-template-columns:100px 1fr;align-items:center}.work-item-details-fields dt{color:var(--yp-text-secondary)}.work-item-details-fields dd{margin:0}.work-item-details-copy{white-space:pre-wrap;overflow-wrap:anywhere}
</style>
