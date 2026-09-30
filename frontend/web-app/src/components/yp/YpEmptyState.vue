<script setup lang="ts">
import { Box, Lock, Search } from '@element-plus/icons-vue'
import { ElEmpty, ElIcon } from 'element-plus'
import { computed } from 'vue'
import OrbitHalo from '../motion/OrbitHalo.vue'

const props = withDefaults(defineProps<{
  reason?: 'empty' | 'no-results' | 'forbidden'
  title?: string | null
  description?: string | null
  compact?: boolean
  /** Decorative orbit behind the icon for page-level empty states; ignored when compact. */
  ambient?: boolean
}>(), {
  reason: 'empty',
  title: null,
  description: null,
  compact: false,
  ambient: false,
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
    :class="{ 'yp-empty-state--compact': compact, 'yp-empty-state--ambient': ambient && !compact }"
  >
    <template #image>
      <div
        class="yp-empty-state__visual"
        aria-hidden="true"
      >
        <orbit-halo v-if="ambient && !compact" />
        <div class="yp-empty-state__icon">
          <slot name="icon">
            <el-icon>
              <component :is="content.icon" />
            </el-icon>
          </slot>
        </div>
      </div>
    </template>
    <template #description>
      <h3>{{ title ?? content.title }}</h3>
      <p v-if="description ?? content.description">
        {{ description ?? content.description }}
      </p>
    </template>
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

.yp-empty-state__visual {
  position: relative;
  display: grid;
  place-items: center;
}

.yp-empty-state--ambient .yp-empty-state__visual {
  width: 132px;
  height: 132px;
  margin: 0 auto;
}

.yp-empty-state--ambient :deep(.el-empty__image) {
  width: auto;
}

.yp-empty-state--ambient h3 {
  font-size: var(--yp-type-section-title-size);
  line-height: var(--yp-type-section-title-line);
}

.yp-empty-state--ambient .yp-empty-state__icon {
  position: relative;
  border: 1px solid var(--yp-border-subtle);
  background: var(--yp-bg-surface);
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

.yp-empty-state__icon .el-icon,
.yp-empty-state__icon :deep(.el-icon) {
  font-size: inherit;
}

h3 {
  margin: 0 0 var(--yp-space-1);
  color: var(--yp-text-primary);
  font-size: var(--yp-type-card-title-size);
  line-height: var(--yp-type-card-title-line);
}

p {
  max-width: 30em;
  margin: 0 auto;
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
  line-height: var(--yp-type-body-line);
  text-wrap: balance;
}
</style>
