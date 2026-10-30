<script setup lang="ts">
import { ElPopover } from 'element-plus'
import YpOverflowText from '../yp/YpOverflowText.vue'
import '../projects/workItemAccentBar.css'
import { MIGRATED_TIME_NOTE } from '../timer/timeCategories'
import { hoursMinutes, type DayProject } from './teamDashboardModel'

/** One day's hours by project and work item for 我的工时 and 成员工时: rows keep the popover width, long names end in an ellipsis. */
defineProps<{ anchor: HTMLElement; title: string; totalMs: number; projects: DayProject[]; colors: Map<string, string>; emptyText?: string }>()
</script>

<template>
  <el-popover
    :visible="true"
    virtual-triggering
    :virtual-ref="anchor"
    placement="bottom"
    :offset="4"
    :show-arrow="false"
    :width="380"
    :persistent="false"
    popper-class="day-breakdown-popover"
    transition="day-breakdown-pop"
  >
    <header class="day-breakdown__head">
      <strong>{{ title }}</strong>
      <span>{{ hoursMinutes(totalMs) }}</span>
      <slot name="action" />
    </header>
    <p
      v-if="!projects.length && emptyText"
      class="day-breakdown__empty"
    >
      {{ emptyText }}
    </p>
    <section
      v-for="(project, index) in projects"
      :key="project.projectId"
      class="day-breakdown__project"
      :style="{ '--day-project': colors.get(project.projectId), '--i': Math.min(index, 8) }"
    >
      <h4>
        <YpOverflowText
          class="day-breakdown__project-name"
          :text="project.projectName"
        />
        <small>{{ hoursMinutes(project.totalMs) }}</small>
      </h4>
      <div
        v-for="(item, order) in project.items"
        :key="item.key"
        class="day-breakdown__item work-item-accent-bar"
        :style="{ '--work-item-accent': colors.get(project.projectId), '--j': Math.min(order, 8) }"
      >
        <span class="day-breakdown__text">
          <YpOverflowText
            tag="strong"
            :text="item.title"
          />
          <small>{{ [item.itemNo, item.migrated ? MIGRATED_TIME_NOTE : ''].filter(Boolean).join(' · ') }}</small>
        </span>
        <span class="day-breakdown__hours">{{ hoursMinutes(item.ms) }}</span>
      </div>
    </section>
  </el-popover>
</template>

<style>
.day-breakdown-popover.el-popover.el-popper{box-sizing:border-box;max-width:calc(100vw - 32px);max-height:min(480px,calc(100vh - 120px));overflow:hidden auto;padding:14px;border-radius:var(--yp-radius-lg);box-shadow:var(--yp-shadow-popover)}
.day-breakdown__head{display:flex;align-items:baseline;justify-content:space-between;gap:12px}
.day-breakdown__head strong{font-size:14px;font-weight:600}
.day-breakdown__head>span{font:700 16px/1 var(--yp-font-mono)}
.day-breakdown__empty{margin:10px 0 0;color:var(--yp-text-secondary);font-size:12px}
.day-breakdown__project{display:grid;grid-template-columns:minmax(0,1fr);gap:4px;animation:day-breakdown-rise 260ms var(--yp-ease-standard) backwards;animation-delay:calc(var(--i,0) * 50ms)}
.day-breakdown__project h4{display:flex;align-items:baseline;gap:6px;min-width:0;margin:10px 0 2px;color:var(--day-project);font-family:var(--yp-font-heading);font-size:14px;font-weight:600}
.day-breakdown__project-name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.day-breakdown__project h4 small{flex:none;margin-left:auto;color:var(--yp-text-secondary);font:600 12px/1 var(--yp-font-mono)}
.day-breakdown__item{display:flex;align-items:center;gap:10px;min-width:0;min-height:38px;padding:0 10px 0 16px;border:1px solid var(--yp-monday-grid-border);background:var(--yp-bg-surface);animation:day-breakdown-rise 260ms var(--yp-ease-standard) backwards;animation-delay:calc(var(--i,0) * 50ms + var(--j,0) * 35ms + 40ms);transition:background-color var(--yp-motion-fast) var(--yp-ease-standard)}
.day-breakdown__item:hover{background:color-mix(in srgb,var(--day-project) 6%,var(--yp-bg-surface))}
.day-breakdown__item.work-item-accent-bar::before{top:-1px;bottom:-1px;left:-1px}
.day-breakdown__text{display:flex;flex:1;flex-direction:column;min-width:0;line-height:18px}
.day-breakdown__text strong{overflow:hidden;font-size:13px;font-weight:500;text-overflow:ellipsis;white-space:nowrap}
.day-breakdown__text small{color:var(--yp-text-muted);font:400 11px/16px var(--yp-font-mono)}
.day-breakdown__hours{flex:none;font:700 13px/1 var(--yp-font-mono)}
.day-breakdown-pop-enter-active{transition:opacity var(--yp-motion-popover) ease-out,translate var(--yp-motion-overlay) var(--yp-ease-standard)}
.day-breakdown-pop-leave-active{transition:opacity var(--yp-motion-fast) ease-in,translate var(--yp-motion-fast) ease-in}
.day-breakdown-pop-enter-from,.day-breakdown-pop-leave-to{opacity:0;translate:0 -6px}
@keyframes day-breakdown-rise{from{opacity:0;transform:translateY(6px)}}
@media(prefers-reduced-motion:reduce){.day-breakdown__project,.day-breakdown__item{animation:none}}
</style>
