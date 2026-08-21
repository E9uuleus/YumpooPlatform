<script setup lang="ts">
import { Box, Lock, Search } from '@element-plus/icons-vue'
import { ElEmpty, ElIcon } from 'element-plus'
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  reason?: 'empty' | 'no-results' | 'forbidden'
  title?: string | null
  description?: string | null
  compact?: boolean
}>(), {
  reason: 'empty',
  title: null,
  description: null,
  compact: false,
})

const content = computed(() => ({
  empty: { title: '暂无数据', description: '这里还没有可显示的内容。', icon: Box },
  'no-results': { title: '没有匹配结果', description: '请调整筛选条件后重试。', icon: Search },
  forbidden: { title: '无权查看', description: '当前账号没有查看此内容所需的权限。', icon: Lock },
}[props.reason]))
</script>

<template>
  <el-empty
    class="yp-empty-state"
    :class="{ 'yp-empty-state--compact': compact }"
    :description="description ?? content.description"
  >
    <template #image>
      <div
        class="yp-empty-state__icon"
        aria-hidden="true"
      >
        <el-icon>
          <component :is="content.icon" />
        </el-icon>
      </div>
    </template>
    <h3>{{ title ?? content.title }}</h3>
    <slot name="action" />
  </el-empty>
</template>

<style scoped>
.yp-empty-state {
  padding: var(--yp-space-8);
  border: 0;
  border-radius: 0;
  background: transparent;
}

.yp-empty-state--compact {
  min-height: 168px;
  padding: var(--yp-space-5);
}

.yp-empty-state--compact .yp-empty-state__icon {
  width: 36px;
  height: 36px;
  font-size: 22px;
}

.yp-empty-state--compact :deep(.el-empty__image) {
  height: 40px;
  margin-bottom: var(--yp-space-2);
}

.yp-empty-state--compact :deep(.el-empty__description) {
  margin-top: var(--yp-space-1);
}

.yp-empty-state__icon {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border-radius: var(--yp-radius-lg);
  color: var(--yp-link);
  background: var(--yp-bg-selected);
  font-size: 28px;
}

.yp-empty-state__icon .el-icon {
  font-size: inherit;
}

h3 {
  margin: 0 0 var(--yp-space-2);
  color: var(--yp-text-primary);
  font-size: var(--yp-type-card-title-size);
}
</style>
