<script setup lang="ts">
import { computed } from 'vue'
import type { DesktopPreferencesChange, DesktopPreferencesState, RailMode, RailProject, RailSize, TimerDockSide, TimerPreferences, TimerPreferencesChange } from '@yumpoo/preload-contract'
import { ElRadioButton, ElRadioGroup, ElSwitch } from 'element-plus'
import { Monitor } from '@element-plus/icons-vue'
import SettingsCard from './SettingsCard.vue'
import ShortcutRecorder from './ShortcutRecorder.vue'
import RailProjectList from './RailProjectList.vue'

const props = defineProps<{
  preferences: DesktopPreferencesState
  timer: TimerPreferences
  pinned: boolean
  /** Rail projects of the signed-in person; their recent projects until they arrange the list. */
  projects: RailProject[]
  defaults: RailProject[]
  busy: boolean
}>()
const emit = defineEmits<{
  change: [change: DesktopPreferencesChange]
  timer: [change: TimerPreferencesChange]
  pin: [pinned: boolean]
  shortcut: [accelerator: string | null]
  projects: [items: RailProject[]]
}>()
const summary = computed(() => `${props.pinned ? '浮在其他应用上方' : '普通窗口'}${props.preferences.railEnabled ? '' : ' · 侧边栏已关闭'}`)
</script>

<template>
  <SettingsCard
    title="窗口"
    :icon="Monitor"
    tag="仅桌面端"
    :summary="summary"
  >
    <div class="settings-row">
      <div class="settings-row__label">
        <strong>显示层级</strong>
        <span>悬浮球与侧边栏浮在其他应用上方；选「普通窗口」会被其他应用遮挡。</span>
      </div>
      <el-radio-group
        :model-value="pinned"
        size="small"
        aria-label="显示层级"
        @update:model-value="value => emit('pin', value === true)"
      >
        <el-radio-button :value="true">
          浮在其他应用上方
        </el-radio-button>
        <el-radio-button :value="false">
          普通窗口
        </el-radio-button>
      </el-radio-group>
    </div>
    <div class="settings-row">
      <div class="settings-row__label">
        <strong>保持在任务栏上方（实验性）</strong>
        <span>侧边栏与任务栏重叠时，切换应用后尝试重新移到最上层；部分切换过程可能短暂闪烁。</span>
      </div>
      <el-switch
        :model-value="preferences.aboveTaskbar"
        :disabled="busy || !pinned"
        aria-label="保持在任务栏上方"
        @update:model-value="value => emit('change', { aboveTaskbar: value === true })"
      />
    </div>
    <div class="settings-row">
      <div class="settings-row__label">
        <strong>快捷键</strong>
        <span v-if="preferences.shortcut && !preferences.shortcutActive">未生效：已被系统或其他应用占用，请换一个组合键。</span>
        <span v-else>全局快捷键，用来显示或隐藏快捷工时小窗口。</span>
      </div>
      <ShortcutRecorder
        :value="preferences.shortcut"
        :busy="busy"
        @change="value => emit('shortcut', value)"
      />
    </div>
    <div class="settings-section">
      浮窗与系统托盘
    </div>
    <div class="settings-row">
      <div class="settings-row__label">
        <strong>项目侧边栏</strong>
        <span>收在屏幕边缘的窄长栏，显示常用项目。鼠标推到边缘即可滑出，点击项目图标直接填报工时，拖动可移动或换边。</span>
      </div>
      <el-switch
        :model-value="preferences.railEnabled"
        :disabled="busy"
        aria-label="项目侧边栏"
        @update:model-value="value => emit('change', { railEnabled: value === true })"
      />
    </div>
    <div
      class="rail-options"
      :class="{ 'is-closed': !preferences.railEnabled }"
    >
      <div :inert="!preferences.railEnabled">
        <div class="rail-options__inner">
          <div class="settings-row">
            <div class="settings-row__label">
              <strong>显示方式</strong>
            </div>
            <el-radio-group
              :model-value="preferences.railMode"
              size="small"
              aria-label="侧边栏显示方式"
              @update:model-value="value => emit('change', { railMode: value as RailMode })"
            >
              <el-radio-button value="autohide">
                自动隐藏
              </el-radio-button>
              <el-radio-button value="always">
                始终显示
              </el-radio-button>
            </el-radio-group>
          </div>
          <div class="settings-row">
            <div class="settings-row__label">
              <strong>屏幕边缘</strong>
            </div>
            <el-radio-group
              :model-value="timer.dockSide"
              size="small"
              aria-label="屏幕边缘"
              @update:model-value="value => emit('timer', { dockSide: value as TimerDockSide })"
            >
              <el-radio-button value="left">
                左侧
              </el-radio-button>
              <el-radio-button value="right">
                右侧
              </el-radio-button>
            </el-radio-group>
          </div>
          <div class="settings-row">
            <div class="settings-row__label">
              <strong>大小</strong>
            </div>
            <el-radio-group
              :model-value="preferences.railSize"
              size="small"
              aria-label="侧边栏大小"
              @update:model-value="value => emit('change', { railSize: value as RailSize })"
            >
              <el-radio-button value="small">
                小
              </el-radio-button>
              <el-radio-button value="medium">
                中
              </el-radio-button>
              <el-radio-button value="large">
                大
              </el-radio-button>
            </el-radio-group>
          </div>
          <div class="settings-row">
            <div class="settings-row__label">
              <strong>顶部显示计时</strong>
              <span>计时器开启时，在侧边栏顶部显示当前计时；关闭后改用悬浮球。</span>
            </div>
            <el-switch
              :model-value="timer.display === 'dock'"
              aria-label="顶部显示计时"
              @update:model-value="value => emit('timer', { display: value ? 'dock' : 'orb' })"
            />
          </div>
          <RailProjectList
            :items="projects"
            :defaults="defaults"
            @update="items => emit('projects', items)"
          />
        </div>
      </div>
    </div>
  </SettingsCard>
</template>

<style scoped>
.rail-options{display:grid;grid-template-rows:1fr;transition:grid-template-rows .26s cubic-bezier(.2,.8,.2,1),opacity .2s}
.rail-options.is-closed{grid-template-rows:0fr;opacity:.3}
.rail-options>div{min-height:0;overflow:hidden}
.rail-options__inner{margin-left:16px;border-left:1px solid var(--yp-border-default)}
@media(prefers-reduced-motion:reduce){.rail-options{transition:none}}
</style>
