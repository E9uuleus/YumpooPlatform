<script setup lang="ts">
import { workItemLabelColorValue } from '../../projects/workItemLabelColors'
import { durationLabel, type ItemTotal } from './dayModel'

defineProps<{ totals: ItemTotal[] }>()
</script>

<template>
  <aside
    class="item-shelf"
    aria-label="工作项"
  >
    <section class="item-shelf__section">
      <h2>今天已记录</h2>
      <p
        v-if="!totals.length"
        class="item-shelf__empty"
      >
        还没有记录。在右侧时间线的空白处拖选一段时间开始补录。
      </p>
      <ul v-else>
        <li
          v-for="item in totals"
          :key="item.workItemId"
          class="item-shelf__row"
          :style="{ '--shelf-item-color': workItemLabelColorValue(item.colorToken ?? undefined) }"
        >
          <span class="item-shelf__text">
            <strong>{{ item.title }}</strong>
            <small>{{ item.itemNo ? `#${item.itemNo} · ` : '' }}{{ item.projectName ?? '' }}</small>
          </span>
          <span class="item-shelf__ms">{{ durationLabel(item.ms) }}</span>
        </li>
      </ul>
    </section>
  </aside>
</template>

<style scoped>
.item-shelf{display:flex;flex-direction:column;gap:16px;min-height:0;overflow:auto;padding:14px 12px;border:1px solid var(--yp-border-subtle);border-radius:var(--yp-radius-md);background:var(--yp-bg-surface)}
.item-shelf__section h2{margin:0 0 6px;color:var(--yp-text-muted);font-size:12px;font-weight:500}
.item-shelf__section ul{display:grid;gap:2px;margin:0;padding:0;list-style:none}
.item-shelf__empty{margin:0;color:var(--yp-text-muted);font-size:12px;line-height:18px}
.item-shelf__row{position:relative;display:flex;align-items:center;gap:8px;min-height:40px;padding:4px 6px 4px 12px;border-radius:var(--yp-radius-sm)}
.item-shelf__row::before{content:'';position:absolute;top:8px;bottom:8px;left:3px;width:3px;border-radius:2px;background:var(--shelf-item-color)}
.item-shelf__text{display:grid;flex:1;min-width:0;gap:1px}
.item-shelf__text strong{overflow:hidden;color:var(--yp-text-primary);font-size:13px;font-weight:500;white-space:nowrap;text-overflow:ellipsis}
.item-shelf__text small{overflow:hidden;color:var(--yp-text-muted);font-size:11px;white-space:nowrap;text-overflow:ellipsis}
.item-shelf__ms{color:var(--yp-text-secondary);font:600 12px var(--yp-font-mono);font-variant-numeric:tabular-nums}
</style>
