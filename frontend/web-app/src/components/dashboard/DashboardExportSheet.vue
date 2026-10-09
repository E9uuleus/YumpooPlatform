<script setup lang="ts">
import { computed } from 'vue'
import type { DashboardChartResult, DashboardWidget } from '@yumpoo/api-client'
import DashboardChart from './DashboardChart.vue'
import { resolveChart } from './chartModel'
import { EXPORT_COLUMN, EXPORT_GRID_WIDTH, EXPORT_PAGE, type ExportPage } from './dashboardExportLayout'

const props = defineProps<{ name: string; exportedAt: string; projects: string[]; filters: string[]; pages: ExportPage[]; results: Map<string, DashboardChartResult> }>()
const px = (value: number) => `${value}px`
const meta = computed(() => [`导出时间 ${props.exportedAt}`, props.projects.length ? `连接项目：${props.projects.join('、')}` : ''].filter(Boolean).join(' · '))
function gridStyle(page: ExportPage) {
  return { width: px(EXPORT_GRID_WIDTH), height: px((page.bottom - page.top) * EXPORT_PAGE.row), margin: px(-EXPORT_PAGE.gutter),
    ...(page.scale < 1 ? { transform: `scale(${page.scale})`, transformOrigin: 'top center' } : {}) }
}
function slotStyle(widget: DashboardWidget, page: ExportPage) {
  return { left: px(widget.wide.x * EXPORT_COLUMN), top: px((widget.wide.y - page.top) * EXPORT_PAGE.row),
    width: px(widget.wide.w * EXPORT_COLUMN), height: px(widget.wide.h * EXPORT_PAGE.row), padding: px(EXPORT_PAGE.gutter) }
}
</script>

<template>
  <div class="dashboard-export yp-theme-light">
    <section
      v-for="(page, index) in pages"
      :key="page.top"
      class="dashboard-export-page"
      :style="{ width: px(EXPORT_PAGE.width), height: px(EXPORT_PAGE.height), padding: px(EXPORT_PAGE.padding) }"
    >
      <header
        v-if="index === 0"
        class="dashboard-export-cover"
        :style="{ height: px(EXPORT_PAGE.cover) }"
      >
        <h1>{{ name }}</h1>
        <p>{{ meta }}</p>
        <p v-if="filters.length">
          筛选：{{ filters.join('；') }}
        </p>
      </header>
      <header
        v-else
        class="dashboard-export-running"
        :style="{ height: px(EXPORT_PAGE.running) }"
      >
        {{ name }}
      </header>
      <div
        class="dashboard-export-body"
        :style="{ height: px((page.bottom - page.top) * EXPORT_PAGE.row * page.scale) }"
      >
        <div
          class="dashboard-export-grid"
          :style="gridStyle(page)"
        >
          <div
            v-for="widget in page.widgets"
            :key="widget.id"
            class="dashboard-export-slot"
            :style="slotStyle(widget, page)"
          >
            <article
              class="dashboard-card dashboard-export-card"
              :class="{ 'dashboard-card--metric': resolveChart(widget).type === 'NUMBER' }"
            >
              <header class="dashboard-card__header">
                <h2>{{ widget.title }}</h2>
              </header>
              <div class="dashboard-card__chart">
                <DashboardChart
                  :widget="widget"
                  :result="results.get(widget.id)"
                  printing
                />
              </div>
            </article>
          </div>
        </div>
      </div>
      <footer
        class="dashboard-export-footer"
        :style="{ height: px(EXPORT_PAGE.footer) }"
      >
        <span>Yumpoo · {{ name }}</span><span>第 {{ index + 1 }} / {{ pages.length }} 页</span>
      </footer>
    </section>
  </div>
</template>

<style>
.dashboard-export-host{position:fixed;top:0;left:-20000px;pointer-events:none}
.dashboard-export{display:flex;flex-direction:column;gap:16px;color:var(--yp-text-primary);font-family:var(--yp-font-family)}
.dashboard-export-page{position:relative;display:flex;flex-direction:column;box-sizing:border-box;overflow:hidden;background:var(--yp-bg-sunken)}
.dashboard-export-cover{display:flex;flex-direction:column;justify-content:center;gap:4px;flex:none;min-width:0}
.dashboard-export-cover h1{margin:0;font:600 26px/36px var(--yp-font-heading);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dashboard-export-cover p{margin:0;color:var(--yp-text-secondary);font-size:13px;line-height:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dashboard-export-running{flex:none;color:var(--yp-text-secondary);font-size:13px;line-height:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dashboard-export-body{flex:none}
.dashboard-export-grid{position:relative}
.dashboard-export-slot{position:absolute;box-sizing:border-box}
.dashboard-export-card{border:1px solid var(--yp-border-default);border-radius:8px;background:var(--yp-bg-surface);box-shadow:0 2px 5px rgb(0 0 0 / 1%);overflow:hidden;box-sizing:border-box}
.dashboard-export-footer{display:flex;align-items:flex-end;justify-content:space-between;margin-top:auto;flex:none;color:var(--yp-text-secondary);font-size:12px;line-height:16px}
</style>
