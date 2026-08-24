<script setup lang="ts">
import { Filter as FilterIcon, Search as SearchIcon } from '@element-plus/icons-vue'
import { ElButton, ElIcon, ElPopover, ElTag, ElTooltip } from 'element-plus'
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { ActiveFilter } from '../../design-system/types'

withDefaults(defineProps<{
  filters?: readonly ActiveFilter[]
  resultCount?: number | null | undefined
  loading?: boolean
  labeledTools?: boolean
  popoverClass?: string
  popoverWidth?: number
  inlineSearch?: boolean
}>(), {
  filters: () => [],
  resultCount: null,
  loading: false,
  labeledTools: false,
  popoverClass: '',
  popoverWidth: 720,
  inlineSearch: false,
})

defineEmits<{
  remove: [key: string]
  clear: []
}>()

const searchOpen = ref(false)
const filtersOpen = ref(false)
const searchPanel = ref<HTMLElement>()

function closePanelsOnEscape(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  searchOpen.value = false
  filtersOpen.value = false
}

onMounted(() => document.addEventListener('keydown', closePanelsOnEscape))
onBeforeUnmount(() => document.removeEventListener('keydown', closePanelsOnEscape))

async function toggleSearch(): Promise<void> {
  searchOpen.value = !searchOpen.value
  if (!searchOpen.value) return
  await nextTick()
  searchPanel.value
    ?.querySelector<HTMLElement>('input:not([type="hidden"]), textarea, select, button, [tabindex]:not([tabindex="-1"])')
    ?.focus()
}
</script>

<template>
  <section
    class="yp-filter-bar"
    aria-label="筛选条件"
    @keydown.esc="closePanelsOnEscape"
  >
    <div class="yp-filter-bar__toolbar">
      <div class="yp-filter-bar__tools">
        <template v-if="$slots.search">
          <el-tooltip
            content="搜索"
            placement="top"
            :disabled="labeledTools"
          >
            <button
              class="yp-filter-bar__tool"
              :class="{ active: searchOpen, labeled: labeledTools }"
              type="button"
              aria-label="展开搜索"
              :aria-expanded="searchOpen"
              @click="toggleSearch"
            >
              <el-icon aria-hidden="true">
                <search-icon />
              </el-icon>
              <span v-if="labeledTools">搜索</span>
            </button>
          </el-tooltip>
          <div
            v-if="inlineSearch && searchOpen"
            ref="searchPanel"
            class="yp-filter-bar__inline-search"
          >
            <slot name="search" />
          </div>
        </template>
        <el-popover
          v-if="$slots.filters || $slots.default"
          v-model:visible="filtersOpen"
          placement="bottom-start"
          :width="popoverWidth"
          :popper-class="['yp-filter-popover', popoverClass].filter(Boolean).join(' ')"
          trigger="click"
        >
          <template #reference>
            <span class="yp-filter-bar__popover-reference">
              <el-tooltip
                content="筛选"
                placement="top"
                :disabled="labeledTools"
              >
                <button
                  class="yp-filter-bar__tool"
                  :class="{ active: filtersOpen || filters.length, labeled: labeledTools }"
                  type="button"
                  aria-label="展开筛选"
                  :aria-expanded="filtersOpen"
                >
                  <el-icon aria-hidden="true">
                    <filter-icon />
                  </el-icon>
                  <span v-if="labeledTools">筛选</span>
                  <span
                    v-if="filters.length"
                    class="yp-filter-bar__badge"
                  >{{ filters.length }}</span>
                </button>
              </el-tooltip>
            </span>
          </template>
          <div class="yp-filter-panel">
            <div class="yp-filter-panel__header">
              <strong>筛选</strong>
              <el-button
                v-if="filters.length"
                link
                type="primary"
                @click="$emit('clear')"
              >
                清除全部
              </el-button>
            </div>
            <div class="yp-filter-panel__controls">
              <slot name="filters" />
              <slot />
            </div>
          </div>
        </el-popover>
      </div>
      <div
        v-if="filters.length || resultCount !== null"
        class="yp-filter-bar__summary"
      >
        <span
          v-if="resultCount !== null"
          class="yp-filter-bar__count"
        >
          {{ loading ? '正在更新…' : `${resultCount} 条` }}
        </span>
        <el-tag
          v-for="filter in filters"
          :key="filter.key"
          closable
          effect="plain"
          @close="$emit('remove', filter.key)"
        >
          {{ filter.valueLabel }}
        </el-tag>
      </div>
      <div
        v-if="$slots.actions"
        class="yp-filter-bar__actions"
      >
        <slot name="actions" />
      </div>
    </div>
    <div
      v-if="!inlineSearch && searchOpen && $slots.search"
      ref="searchPanel"
      class="yp-filter-bar__search"
    >
      <slot name="search" />
    </div>
  </section>
</template>

<style scoped>
.yp-filter-bar {
  margin-bottom: var(--yp-space-5);
  border-bottom: 1px solid var(--yp-border-subtle);
}

.yp-filter-bar__toolbar {
  display: flex;
  align-items: center;
  min-height: 48px;
  gap: var(--yp-space-2);
}

.yp-filter-bar__tools,
.yp-filter-bar__summary,
.yp-filter-bar__actions,
.yp-filter-bar__search {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-2);
}

.yp-filter-bar__inline-search {
  display: flex;
  align-items: center;
  min-width: min(360px, 50vw);
}

.yp-filter-bar__inline-search :deep(.el-input) {
  width: min(360px, 50vw);
}

.yp-filter-bar__summary {
  min-width: 0;
}

.yp-filter-bar__actions {
  margin-left: auto;
}

.yp-filter-bar__popover-reference {
  display: inline-flex;
}

.yp-filter-bar__tool {
  position: relative;
  display: grid;
  width: var(--yp-control-height);
  height: var(--yp-control-height);
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-secondary);
  background: transparent;
  cursor: pointer;
}

.yp-filter-bar__tool.labeled {
  display: inline-flex;
  width: auto;
  min-width: var(--yp-control-height);
  padding: 0 var(--yp-space-3);
  gap: var(--yp-space-2);
}

.yp-filter-bar__tool:hover,
.yp-filter-bar__tool.active {
  color: var(--yp-text-primary);
  background: var(--yp-bg-sunken);
}

.yp-filter-bar__tool .el-icon {
  width: 18px;
  height: 18px;
  font-size: 18px;
}

.yp-filter-bar__badge {
  position: absolute;
  top: 2px;
  right: 2px;
  display: grid;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  place-items: center;
  border-radius: var(--yp-radius-pill);
  color: var(--yp-text-inverse);
  background: var(--yp-text-primary);
  font-size: 10px;
  line-height: 1;
}

.yp-filter-bar__search {
  padding: var(--yp-space-3) 0;
  border-top: 1px solid var(--yp-border-subtle);
}

.yp-filter-bar__search :deep(.el-input) {
  max-width: 360px;
}

.yp-filter-bar__count {
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
}
</style>

<style>
.yp-filter-popover.el-popover {
  max-width: calc(100vw - var(--yp-space-6));
  padding: 0;
  border-color: var(--yp-border-default);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-raised);
  box-shadow: var(--yp-shadow-popover);
}

.yp-filter-panel {
  padding: var(--yp-space-5);
}

.yp-filter-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--yp-space-4);
  padding-bottom: var(--yp-space-3);
  border-bottom: 1px solid var(--yp-border-subtle);
}

.yp-filter-panel__controls {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--yp-space-3);
  align-items: flex-end;
}

.yp-filter-panel__controls > .el-select,
.yp-filter-panel__controls > .el-input {
  width: 100%;
}
</style>
