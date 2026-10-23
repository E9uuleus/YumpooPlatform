<script setup lang="ts">
import { ElTooltip } from 'element-plus'
import { onBeforeUnmount, ref } from 'vue'

defineOptions({ inheritAttrs: false })
withDefaults(defineProps<{ text: string; tag?: string; placement?: 'top' | 'right' | 'bottom' | 'left' }>(), { tag: 'span', placement: 'top' })
const target = ref<HTMLElement>()
const visible = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
/** Opens only for text that is actually cut off, measured as the pointer or focus arrives, so resizes need no observer. */
function enter() {
  clearTimeout(timer)
  const element = target.value
  if (!element || (element.scrollWidth <= element.clientWidth + 1 && element.scrollHeight <= element.clientHeight + 1)) return
  timer = setTimeout(() => { visible.value = true }, 300)
}
function leave() {
  clearTimeout(timer)
  visible.value = false
}
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <!-- 与密集列表的提示一致：不可进入，离开即消失，只有文字被截断时才出现。 -->
  <el-tooltip
    :content="text"
    :placement="placement"
    :visible="visible"
    :enterable="false"
    :hide-after="0"
  >
    <component
      :is="tag"
      ref="target"
      v-bind="$attrs"
      @mouseenter="enter"
      @mouseleave="leave"
      @focusin="enter"
      @focusout="leave"
    >{{ text }}</component>
  </el-tooltip>
</template>
