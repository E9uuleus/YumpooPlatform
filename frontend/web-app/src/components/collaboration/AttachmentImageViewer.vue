<script setup lang="ts">
import { ElIcon, ElImageViewer } from 'element-plus'
import { Download as DownloadIcon, FullScreen, RefreshLeft, RefreshRight, ZoomIn, ZoomOut } from '@element-plus/icons-vue'

defineProps<{ urlList: string[]; initialIndex: number }>()
const emit = defineEmits<{ close: [] }>()

/** Same-origin attachment content answers with Content-Disposition: attachment; the desktop shell shows a save dialog for it. */
function saveImage(url: string): void {
  const link = document.createElement('a')
  link.href = url
  link.download = ''
  link.click()
}
</script>

<template>
  <el-image-viewer
    :url-list="urlList"
    :initial-index="initialIndex"
    hide-on-click-modal
    teleported
    @close="emit('close')"
  >
    <template #toolbar="{ actions, reset, activeIndex }">
      <el-icon @click="actions('zoomOut')">
        <zoom-out />
      </el-icon>
      <el-icon @click="actions('zoomIn')">
        <zoom-in />
      </el-icon>
      <i class="el-image-viewer__actions__divider" />
      <el-icon @click="reset">
        <full-screen />
      </el-icon>
      <i class="el-image-viewer__actions__divider" />
      <el-icon @click="actions('anticlockwise')">
        <refresh-left />
      </el-icon>
      <el-icon @click="actions('clockwise')">
        <refresh-right />
      </el-icon>
      <i class="el-image-viewer__actions__divider" />
      <el-icon
        role="button"
        aria-label="下载"
        title="下载"
        @click="saveImage(urlList[activeIndex]!)"
      >
        <download-icon />
      </el-icon>
    </template>
  </el-image-viewer>
</template>
