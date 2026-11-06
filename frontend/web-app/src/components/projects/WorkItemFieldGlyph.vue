<script setup lang="ts">
import { ElIcon } from 'element-plus'
import { computed } from 'vue'
import { workItemFieldIdentity } from './workItemFieldIdentity'

/** 字段图标：默认是带字段颜色的圆角方块配白色图标，plain 只显示灰色图标（用于标签内）。 */
const props = withDefaults(defineProps<{ field: string; size?: 16 | 20 | 24; plain?: boolean }>(), { size: 20 })
const identity = computed(() => workItemFieldIdentity(props.field))
</script>

<template>
  <span
    class="work-item-field-glyph"
    :class="{ 'work-item-field-glyph--plain': plain, 'work-item-field-glyph--large': size === 24 }"
    :style="{ '--field-glyph-size': `${size}px`, '--field-glyph-tone': identity.tone }"
    aria-hidden="true"
  >
    <el-icon><component :is="identity.icon" /></el-icon>
  </span>
</template>

<style scoped>
.work-item-field-glyph {
  display: inline-grid;
  width: var(--field-glyph-size);
  height: var(--field-glyph-size);
  flex-shrink: 0;
  place-items: center;
  border-radius: var(--yp-radius-xs);
  color: var(--yp-on-solid);
  background: var(--field-glyph-tone);
  font-size: calc(var(--field-glyph-size) * 0.6);
}

.work-item-field-glyph--large { border-radius: var(--yp-radius-sm); }

.work-item-field-glyph--plain {
  width: auto;
  height: auto;
  color: var(--yp-text-secondary);
  background: none;
  font-size: 14px;
}
</style>
