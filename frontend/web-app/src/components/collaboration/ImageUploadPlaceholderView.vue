<script setup lang="ts">
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { Loading, Picture as PictureIcon, WarningFilled } from '@element-plus/icons-vue'
import { computed } from 'vue'
import type { ImageUploadState } from './descriptionImages'

const props = defineProps(nodeViewProps)
const uploadId = computed(() => String(props.node.attrs.uploadId))
const state = computed<ImageUploadState>(() =>
  (props.extension.options.getUpload as (id: string) => ImageUploadState | undefined)(uploadId.value)
  ?? { name: '图片', phase: 'failed', message: '上传已中断' })
const label = computed(() => state.value.phase === 'uploading' ? '正在上传…'
  : state.value.phase === 'scanning' ? '安全扫描中，通过后自动显示' : state.value.message ?? '上传失败')
function remove() { (props.extension.options.onRemove as (id: string) => void)(uploadId.value) }
</script>

<template>
  <node-view-wrapper
    class="image-upload"
    :class="[`image-upload--${state.phase}`, { 'image-upload--selected': selected }]"
    contenteditable="false"
    data-image-upload
  >
    <span
      class="image-upload__icon"
      aria-hidden="true"
    >
      <warning-filled v-if="state.phase === 'failed'" />
      <loading
        v-else
        class="image-upload__spinner"
      />
    </span>
    <span class="image-upload__text">
      <strong><picture-icon aria-hidden="true" />{{ state.name }}</strong>
      <span role="status">{{ label }}</span>
    </span>
    <button
      type="button"
      class="image-upload__remove"
      :aria-label="`移除 ${state.name}`"
      @mousedown.prevent
      @click="remove"
    >
      {{ state.phase === 'failed' ? '移除' : '取消' }}
    </button>
  </node-view-wrapper>
</template>

<style scoped>
.image-upload { display: flex; align-items: center; gap: 12px; margin: 12px 0; padding: 12px 14px; border: 1px dashed var(--yp-border-strong); border-radius: 8px; background: var(--yp-bg-sunken); color: var(--yp-text-secondary); font-size: 13px; user-select: none; }
.image-upload--selected { outline: 2px solid var(--yp-action-primary); outline-offset: 2px; }
.image-upload--failed { border-color: var(--yp-status-red, #d83a52); color: var(--yp-status-red, #d83a52); }
.image-upload__icon { display: inline-grid; place-items: center; flex: none; width: 32px; height: 32px; border-radius: 50%; background: var(--yp-bg-surface); color: var(--yp-action-primary); }
.image-upload--failed .image-upload__icon { color: inherit; }
.image-upload__icon svg { width: 18px; height: 18px; }
.image-upload__spinner { animation: image-upload-spin 1s linear infinite; }
.image-upload__text { display: grid; gap: 2px; flex: 1; min-width: 0; }
.image-upload__text strong { display: flex; align-items: center; gap: 6px; overflow: hidden; color: var(--yp-text-primary); font-weight: 600; white-space: nowrap; text-overflow: ellipsis; }
.image-upload__text strong svg { flex: none; width: 14px; height: 14px; color: var(--yp-text-muted); }
.image-upload__remove { flex: none; padding: 4px 10px; border: 1px solid var(--yp-border-strong); border-radius: 4px; color: var(--yp-text-secondary); background: var(--yp-bg-surface); cursor: pointer; font: inherit; }
.image-upload__remove:hover { color: var(--yp-action-primary); border-color: var(--yp-action-primary); }
.image-upload__remove:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: 2px; }
@keyframes image-upload-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .image-upload__spinner { animation: none; } }
</style>
