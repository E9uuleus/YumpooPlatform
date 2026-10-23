<script setup lang="ts">
import { ElTooltip } from 'element-plus'
import { ref } from 'vue'

defineOptions({ inheritAttrs: false })
withDefaults(defineProps<{ text: string; tag?: string; placement?: 'top' | 'right' | 'bottom' | 'left' }>(), { tag: 'span', placement: 'top' })
const target = ref<HTMLElement>()
const truncated = ref(false)
/** Measured when the pointer or focus arrives, so text that fits never raises a tooltip and resizes need no observer. */
function measure() {
  const element = target.value
  truncated.value = !!element && (element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1)
}
</script>

<template>
  <!-- 与密集列表的提示一致：不可进入，离开即消失，只有文字被截断时才出现。 -->
  <el-tooltip
    :content="text"
    :placement="placement"
    :disabled="!truncated"
    :enterable="false"
    :show-after="300"
    :hide-after="0"
  >
    <component
      :is="tag"
      ref="target"
      v-bind="$attrs"
      @mouseenter="measure"
      @focusin="measure"
    >{{ text }}</component>
  </el-tooltip>
</template>
