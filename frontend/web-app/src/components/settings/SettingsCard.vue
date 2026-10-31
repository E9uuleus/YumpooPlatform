<script setup lang="ts">
import type { Component } from 'vue'
import { ElIcon } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'

defineProps<{ title: string; icon: Component; tag: string; summary: string }>()
const open = defineModel<boolean>('open', { default: true })
</script>

<template>
  <section
    class="settings-card"
    :class="{ 'is-closed': !open }"
  >
    <button
      type="button"
      class="settings-card__head"
      :aria-expanded="open"
      @click="open = !open"
    >
      <el-icon class="settings-card__icon">
        <component :is="icon" />
      </el-icon>
      <strong>{{ title }}</strong>
      <span class="settings-card__tag">{{ tag }}</span>
      <span class="settings-card__summary">{{ summary }}</span>
      <el-icon class="settings-card__chevron">
        <ArrowDown />
      </el-icon>
    </button>
    <div class="settings-card__body">
      <div :inert="!open">
        <slot />
      </div>
    </div>
  </section>
</template>

<style scoped>
.settings-card{overflow:hidden;border-radius:var(--yp-radius-xl);background:var(--yp-bg-sunken);}
.settings-card__head{display:flex;align-items:center;gap:10px;width:100%;height:48px;padding:0 16px;border:0;background:none;color:var(--yp-text-primary);font:inherit;cursor:pointer;text-align:left;transition:border-color .2s}
.is-closed .settings-card__head{border-bottom-color:transparent}
.settings-card__head:focus-visible{outline:2px solid var(--yp-action-primary);outline-offset:-2px}
.settings-card__icon{color:var(--yp-text-secondary);font-size:18px}
.settings-card__head strong{font-size:14px;font-weight:500}
.settings-card__tag{padding:1px 7px;border-radius:var(--yp-radius-pill);background:var(--yp-bg-sunken);color:var(--yp-text-secondary);font-size:11px}
.settings-card__summary{margin-left:auto;overflow:hidden;color:var(--yp-text-secondary);font-size:12px;text-overflow:ellipsis;white-space:nowrap}
.settings-card__chevron{color:var(--yp-text-secondary);transition:transform .22s cubic-bezier(.2,.8,.2,1)}
.is-closed .settings-card__chevron{transform:rotate(-90deg)}
.settings-card__body{display:grid;grid-template-rows:1fr;transition:grid-template-rows .26s cubic-bezier(.2,.8,.2,1)}
.is-closed .settings-card__body{grid-template-rows:0fr}
.settings-card__body>div{min-height:0;overflow:hidden}
@media(prefers-reduced-motion:reduce){.settings-card *{transition:none!important}}
</style>

<style>
/* Rows shared by the three cards. */
.settings-row{display:flex;align-items:center;gap:16px;padding:11px 16px;border-bottom:1px solid var(--yp-border-subtle)}
.settings-row:last-child{border-bottom:0}
.settings-row__label{flex:1;min-width:0}
.settings-row__label strong{display:block;font-weight:500}
.settings-row__label span{display:block;margin-top:2px;color:var(--yp-text-secondary);font-size:12px;line-height:1.5}
.settings-section{display:flex;align-items:center;gap:8px;padding:12px 16px 2px;color:var(--yp-text-muted);font-size:11.5px}
.settings-section__aside{margin-left:auto}
</style>
