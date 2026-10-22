<script setup lang="ts">
import { Brush as AppearanceIcon } from '@element-plus/icons-vue'
import { ElButton, ElIcon, ElPopover, ElRadioButton, ElRadioGroup } from 'element-plus'
import type { DensityMode, ThemeMode } from '../../composables/useAppearance'

defineProps<{
  theme: ThemeMode
  density: DensityMode
}>()

defineEmits<{
  'update:theme': [value: ThemeMode]
  'update:density': [value: DensityMode]
}>()
</script>

<template>
  <el-popover
    placement="bottom-end"
    :width="300"
    trigger="click"
  >
    <template #reference>
      <el-button class="yp-theme-trigger">
        <el-icon aria-hidden="true">
          <appearance-icon />
        </el-icon>
        外观
      </el-button>
    </template>
    <div class="yp-theme-panel">
      <fieldset>
        <legend>主题</legend>
        <el-radio-group
          :model-value="theme"
          aria-label="主题"
          @update:model-value="$emit('update:theme', $event as ThemeMode)"
        >
          <el-radio-button value="system">
            跟随系统
          </el-radio-button>
          <el-radio-button value="light">
            浅色
          </el-radio-button>
          <el-radio-button value="dark">
            深色
          </el-radio-button>
          <el-radio-button value="night">
            夜间
          </el-radio-button>
        </el-radio-group>
      </fieldset>
      <fieldset>
        <legend>密度</legend>
        <el-radio-group
          :model-value="density"
          aria-label="界面密度"
          @update:model-value="$emit('update:density', $event as DensityMode)"
        >
          <el-radio-button value="comfortable">
            舒适
          </el-radio-button>
          <el-radio-button value="compact">
            紧凑
          </el-radio-button>
        </el-radio-group>
      </fieldset>
    </div>
  </el-popover>
</template>

<style scoped>
.yp-theme-panel {
  display: grid;
  gap: var(--yp-space-5);
}

fieldset {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

legend {
  margin-bottom: var(--yp-space-2);
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
}

.el-radio-group {
  display: flex;
  flex-wrap: wrap;
}

.yp-theme-trigger .el-icon {
  font-size: 18px;
}
</style>
