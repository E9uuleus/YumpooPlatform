<script setup lang="ts">
import { HideCompletedEffect } from '@yumpoo/api-client'
import { computed, ref } from 'vue'
import { ElCheckbox, ElPopover, ElRadio, ElRadioGroup } from 'element-plus'
import HideCompletedSwitch from '../timer/HideCompletedSwitch.vue'
import { hideCompletedEffect, setHideCompletedEffect } from '../../composables/useHideCompleted'
import { isCompletedStatus } from './workItemTableSettings'

const props = defineProps<{
  statuses: ReadonlyArray<{ code: string; displayName: string; statusCategory: string }>
  codes: ReadonlySet<string> | null
  disabled: boolean
  /** 个人表格设置已读取，才能保存完成定义。 */
  editable: boolean
}>()
const emit = defineEmits<{ update: [codes: Set<string>] }>()
const EFFECTS = [
  { value: HideCompletedEffect.Fade, label: '文字置灰' },
  { value: HideCompletedEffect.FadeStrikethrough, label: '文字置灰 + 删除线' },
  { value: HideCompletedEffect.Hide, label: '不显示' },
]
const open = ref(false)
const savingEffect = ref(false)
const defaults = computed(() => props.statuses.filter(status => isCompletedStatus(null, status.code, status.statusCategory))
  .map(status => status.displayName).join('、') || '无')

function toggle(code: string, checked: boolean) {
  emit('update', new Set(props.statuses.filter(status => status.code === code ? checked
    : isCompletedStatus(props.codes, status.code, status.statusCategory)).map(status => status.code)))
}

async function changeEffect(value: HideCompletedEffect): Promise<void> {
  savingEffect.value = true
  try { await setHideCompletedEffect(value) } finally { savingEffect.value = false }
}
</script>

<template>
  <span class="work-item-completed-menu">
    <HideCompletedSwitch
      toolbar
      :disabled="disabled"
    />
    <el-popover
      v-model:visible="open"
      placement="bottom-end"
      :width="252"
      trigger="click"
      :disabled="disabled"
      popper-class="work-items-popover work-item-view-control"
    >
      <template #reference>
        <button
          type="button"
          class="work-item-completed-menu__toggle"
          :disabled="disabled"
          :aria-expanded="open"
          aria-label="隐藏效果与已完成状态"
        >
          <svg
            width="14"
            height="14"
            viewBox="0 0 20 20"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M5 8l5 5 5-5"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </button>
      </template>
      <section
        class="work-item-completed-menu__panel"
        aria-label="隐藏效果"
      >
        <p>隐藏效果（仅自己）</p>
        <el-radio-group
          :model-value="hideCompletedEffect"
          :disabled="savingEffect"
          @change="value => changeEffect(value as HideCompletedEffect)"
        >
          <el-radio
            v-for="effect in EFFECTS"
            :key="effect.value"
            :value="effect.value"
          >
            {{ effect.label }}
          </el-radio>
        </el-radio-group>
        <small>选择「不显示」时，搜索或按状态筛选仍会列出已完成的工作项。</small>
      </section>
      <section
        class="work-item-completed-menu__panel"
        aria-label="已完成状态"
      >
        <p>本项目中视为已完成的状态（仅自己）</p>
        <el-checkbox
          v-for="status in statuses"
          :key="status.code"
          :model-value="isCompletedStatus(codes, status.code, status.statusCategory)"
          :disabled="!editable"
          @change="toggle(status.code, $event === true)"
        >
          {{ status.displayName }}
        </el-checkbox>
        <small>默认：{{ defaults }}</small>
      </section>
    </el-popover>
  </span>
</template>

<style scoped>
.work-item-completed-menu{display:inline-flex;flex:0 0 auto;align-items:center}
.work-item-completed-menu__toggle{display:inline-grid;place-items:center;width:28px;height:36px;padding:0;border:0;border-radius:var(--yp-radius-sm);background:transparent;color:var(--yp-text-secondary);cursor:pointer;transition:background var(--yp-motion-fast) var(--yp-ease-standard),color var(--yp-motion-fast) var(--yp-ease-standard)}
.work-item-completed-menu__toggle:hover:not(:disabled),.work-item-completed-menu__toggle[aria-expanded="true"]{background:var(--yp-bg-hover);color:var(--yp-action-primary)}
.work-item-completed-menu__toggle:disabled{color:var(--yp-text-disabled);cursor:not-allowed}
.work-item-completed-menu__panel{display:flex;flex-direction:column;gap:2px}
.work-item-completed-menu__panel p{margin:0 0 4px;color:var(--yp-text-muted);font-size:12px}
.work-item-completed-menu__panel .el-checkbox{height:28px;margin-right:0}
.work-item-completed-menu__panel small{margin-top:4px;color:var(--yp-text-muted);font-size:12px}
.work-item-completed-menu__panel + .work-item-completed-menu__panel{margin-top:10px;padding-top:10px;}
.work-item-completed-menu__panel .el-radio-group{display:flex;flex-direction:column;align-items:flex-start}
.work-item-completed-menu__panel .el-radio{height:28px;margin-right:0}
</style>
