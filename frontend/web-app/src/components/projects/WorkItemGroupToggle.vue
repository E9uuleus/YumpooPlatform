<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  collapsed: boolean
  /** 读屏文案中的对象：「收起分组：张三」「展开项目：平台改版」。 */
  subject?: string
}>(), { subject: '分组' })
defineEmits<{ toggle: [] }>()
</script>

<template>
  <button
    type="button"
    class="work-item-group-toggle"
    :aria-expanded="!collapsed"
    :aria-label="`${collapsed ? '展开' : '收起'}${subject}：${label}`"
    @click.stop="$emit('toggle')"
  >
    <svg
      viewBox="0 0 20 20"
      width="16"
      height="16"
      fill="currentColor"
      aria-hidden="true"
      :class="{ expanded: !collapsed }"
    >
      <path d="M12.76 10.56a.77.77 0 0 0 0-1.116L8.397 5.233a.84.84 0 0 0-1.157 0 .77.77 0 0 0 0 1.116l3.785 3.653-3.785 3.652a.77.77 0 0 0 0 1.117.84.84 0 0 0 1.157 0l4.363-4.211Z" />
    </svg>
    <span class="work-item-group-name">{{ label }}</span>
    <slot />
  </button>
</template>

<style scoped>
/* 只声明与表格分组标题相同取值的外观；定位、尺寸与内边距由使用方设置。 */
.work-item-group-toggle { display: flex; align-items: center; gap: 10px; border: 0; background: transparent; color: var(--yp-text-primary); text-align: left; cursor: pointer; }
.work-item-group-toggle svg { flex: none; transition: transform 140ms ease; }
.work-item-group-toggle svg.expanded { transform: rotate(90deg); }
.work-item-group-name { color: var(--work-item-group-accent); font-size: 16px; font-weight: 600; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.work-item-group-toggle :slotted(small) { flex: none; font-size: 12px; color: var(--yp-text-secondary); font-weight: 400; }
@media (prefers-reduced-motion: reduce) { .work-item-group-toggle svg { transition: none; } }
</style>
