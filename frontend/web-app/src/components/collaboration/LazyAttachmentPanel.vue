<script setup lang="ts">
import { AttachmentOwnerType } from '@yumpoo/api-client'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElIcon } from 'element-plus'
import { ref } from 'vue'
import AttachmentPanel from './AttachmentPanel.vue'

defineProps<{
  ownerType: AttachmentOwnerType.WorkItem | AttachmentOwnerType.WorkItemUpdate
  ownerId: string
  canUpload: boolean
}>()

const expanded = ref(false)
</script>

<template>
  <section class="lazy-attachments">
    <button type="button" class="lazy-attachments__toggle" :aria-expanded="expanded" @click="expanded = !expanded">
      <span>附件</span>
      <el-icon :class="{ expanded }"><arrow-down /></el-icon>
    </button>
    <attachment-panel
      v-if="expanded"
      :owner-type="ownerType"
      :owner-id="ownerId"
      :can-upload="canUpload"
    />
  </section>
</template>

<style scoped>
.lazy-attachments { border-top: 1px solid var(--yp-border-subtle); }
.lazy-attachments__toggle {
  display: flex; width: 100%; align-items: center; justify-content: space-between;
  padding: var(--yp-space-3) 0; border: 0; color: var(--yp-text-primary);
  background: transparent; font: inherit; font-weight: 600; cursor: pointer;
}
.lazy-attachments__toggle .el-icon { transition: transform var(--yp-motion-fast) var(--yp-ease-standard); }
.lazy-attachments__toggle .el-icon.expanded { transform: rotate(180deg); }
</style>
