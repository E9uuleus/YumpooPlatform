<script setup lang="ts">
import { computed } from 'vue'
import { ElRadioButton, ElRadioGroup, ElSlider } from 'element-plus'
import { Brush } from '@element-plus/icons-vue'
import SettingsCard from './SettingsCard.vue'
import { useAppearance, type DensityMode, type ThemeMode } from '../../composables/useAppearance'
import { useDesktopSurfaceOpacity } from '../../composables/useDesktopSurfaceOpacity'

defineProps<{ desktop: boolean }>()
const appearance = useAppearance()
const surface = useDesktopSurfaceOpacity()
const THEMES: Record<ThemeMode, string> = { system: '跟随系统', light: '浅色', dark: '深色', night: '夜间' }
const DENSITIES: Record<DensityMode, string> = { comfortable: '舒适', compact: '紧凑' }
const summary = computed(() => `${THEMES[appearance.themeMode.value]} · ${DENSITIES[appearance.densityMode.value]}`)
</script>

<template>
  <SettingsCard
    title="外观"
    :icon="Brush"
    tag="Web 与桌面端"
    :summary="summary"
  >
    <div class="settings-row">
      <div class="settings-row__label">
        <strong>主题</strong>
        <span>夜间为低眩光深海军蓝，只能手动开启。</span>
      </div>
      <el-radio-group
        :model-value="appearance.themeMode.value"
        size="small"
        aria-label="主题"
        @update:model-value="value => appearance.setThemeMode(value as ThemeMode)"
      >
        <el-radio-button
          v-for="(label, value) in THEMES"
          :key="value"
          :value="value"
        >
          {{ label }}
        </el-radio-button>
      </el-radio-group>
    </div>
    <div class="settings-row">
      <div class="settings-row__label">
        <strong>界面密度</strong>
      </div>
      <el-radio-group
        :model-value="appearance.densityMode.value"
        size="small"
        aria-label="界面密度"
        @update:model-value="value => appearance.setDensityMode(value as DensityMode)"
      >
        <el-radio-button
          v-for="(label, value) in DENSITIES"
          :key="value"
          :value="value"
        >
          {{ label }}
        </el-radio-button>
      </el-radio-group>
    </div>
    <div
      v-if="desktop"
      class="settings-row"
    >
      <div class="settings-row__label">
        <strong>小窗口与侧边栏不透明度</strong>
      </div>
      <el-slider
        class="appearance-opacity"
        :model-value="surface.opacity.value"
        :min="70"
        :max="100"
        :step="5"
        :show-tooltip="false"
        aria-label="小窗口与侧边栏不透明度"
        @update:model-value="value => surface.setOpacity(Number(value))"
      />
      <span class="appearance-opacity__value">{{ surface.opacity.value }}%</span>
    </div>
  </SettingsCard>
</template>

<style scoped>
.appearance-opacity{width:140px;flex-shrink:0}
.appearance-opacity__value{min-width:36px;text-align:right;font-variant-numeric:tabular-nums}
</style>
