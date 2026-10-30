<script setup lang="ts">
import { UploadFilled } from '@element-plus/icons-vue'
import { ElIcon } from 'element-plus'
import { ref } from 'vue'
import { SPREADSHEET_EXTENSIONS, validateSpreadsheetFile } from './spreadsheet'

const props = withDefaults(defineProps<{
  /** 拖放区下方的说明文字。 */
  hint?: string
  disabled?: boolean
}>(), {
  hint: '拖入文件或点击选择 · 支持 .csv、.xlsx、.xls，最大 5 MB',
  disabled: false,
})

const emit = defineEmits<{ file: [file: File] }>()

const input = ref<HTMLInputElement>()
const dragging = ref(false)
const error = ref('')

function accept(file: File | undefined): void {
  if (!file || props.disabled) return
  const invalid = validateSpreadsheetFile(file)
  error.value = invalid ?? ''
  if (!invalid) emit('file', file)
}

function onChange(): void {
  const element = input.value
  accept(element?.files?.[0])
  if (element) element.value = ''
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  accept(event.dataTransfer?.files?.[0])
}

const open = (): void => {
  if (!props.disabled) input.value?.click()
}
</script>

<template>
  <div class="import-drop">
    <div
      class="import-drop__zone"
      :class="{ 'is-dragging': dragging, 'is-disabled': disabled }"
      role="button"
      :tabindex="disabled ? -1 : 0"
      :aria-disabled="disabled"
      aria-label="选择要导入的文件"
      @click="open"
      @keydown.enter.prevent="open"
      @keydown.space.prevent="open"
      @dragenter.prevent="dragging = !disabled"
      @dragover.prevent="dragging = !disabled"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <el-icon
        class="import-drop__icon"
        :size="28"
      >
        <upload-filled />
      </el-icon>
      <span class="import-drop__hint">{{ hint }}</span>
    </div>
    <input
      ref="input"
      type="file"
      :accept="SPREADSHEET_EXTENSIONS.join(',')"
      hidden
      :disabled="disabled"
      @change="onChange"
    >
    <p
      v-if="error"
      class="import-drop__error"
      role="alert"
    >
      {{ error }}
    </p>
  </div>
</template>

<style scoped>
.import-drop {
  display: grid;
  gap: var(--yp-space-2);
}

.import-drop__zone {
  display: flex;
  min-height: 160px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--yp-space-3);
  border: 1px dashed var(--yp-border-strong);
  border-radius: var(--yp-radius-md);
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
  font: 400 13px / 1.5 var(--yp-font-family);
  cursor: pointer;
  transition: border-color var(--yp-motion-fast) ease-out, background-color var(--yp-motion-fast) ease-out;
}

.import-drop__zone:hover,
.import-drop__zone.is-dragging {
  border-color: var(--yp-action-primary);
  background: var(--yp-bg-hover);
}

.import-drop__zone:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 2px;
}

.import-drop__zone.is-disabled {
  color: var(--yp-text-disabled);
  cursor: not-allowed;
}

.import-drop__icon {
  color: var(--yp-text-muted);
}

.import-drop__error {
  margin: 0;
  color: var(--yp-status-red);
  font-size: 13px;
}
</style>
