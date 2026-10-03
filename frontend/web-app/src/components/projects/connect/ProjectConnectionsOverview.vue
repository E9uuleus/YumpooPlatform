<script setup lang="ts">
import { computed, watch } from 'vue'
import { ElButton, ElTag } from 'element-plus'
import type { ConnectColumnCatalog } from '@yumpoo/api-client'
import InlineProblem from '../../InlineProblem.vue'
import { useConnectColumns } from './useConnectColumns'

const props = defineProps<{ projectId: string; catalog?: ConnectColumnCatalog | undefined }>()
const data = useConnectColumns(() => props.projectId, () => !props.catalog)
const catalog = computed(() => props.catalog ?? data.catalog.value)
watch(() => [props.projectId, Boolean(props.catalog)], () => { if (!props.catalog) void data.loadCatalog() }, { immediate: true })
</script>

<template>
  <div
    class="project-connections-overview"
    :aria-busy="data.catalogLoading.value"
  >
    <inline-problem
      v-if="data.catalogError.value"
      :problem="data.catalogError.value"
    />
    <el-button
      v-if="data.catalogError.value"
      text
      @click="data.loadCatalog"
    >
      重新加载连接概览
    </el-button>
    <p
      v-else-if="data.catalogLoading.value && !catalog"
      role="status"
    >
      正在加载连接…
    </p>
    <template v-if="catalog">
      <div
        v-if="!catalog.items.length && !catalog.incomingColumns.length"
        class="unified-project-settings__empty"
      >
        还没有连接。在工作项表格右上角「+」中添加连接列。
      </div>
      <template v-else>
        <p class="project-connections-overview__intro">
          连接在工作项表格中管理，这里只做概览。
        </p>
        <h3>本项目的连接列</h3>
        <div
          v-for="column in catalog.items"
          :key="column.id"
          class="project-connections-overview__row"
        >
          <strong>{{ column.name }}</strong><span aria-hidden="true">→</span>
          <div class="project-connections-overview__targets">
            <el-tag
              v-for="target in column.targets"
              :key="target.projectId"
              type="info"
            >
              {{ target.name }}
            </el-tag>
          </div>
          <router-link :to="{ path: `/projects/${projectId}/overview`, query: { view: 'table', connectColumn: column.id } }">
            在表格中管理
          </router-link>
        </div>
        <p v-if="!catalog.items.length">
          本项目还没有连接列。
        </p>
        <h3>连接到本项目的项目</h3>
        <div
          v-for="column in catalog.incomingColumns"
          :key="column.columnId"
          class="project-connections-overview__row"
        >
          <strong>{{ column.projectName }}</strong><code>{{ column.projectCode }}</code><el-tag type="info">
            {{ column.columnName }}
          </el-tag>
        </div>
        <p v-if="!catalog.incomingColumns.length">
          暂无其他项目连接到本项目。
        </p>
      </template>
    </template>
  </div>
</template>

<style scoped>
.project-connections-overview { display: grid; gap: 10px; font-size: 13px; }
.project-connections-overview h3 { margin: 8px 0 0; color: var(--yp-text-secondary); font-size: 12px; font-weight: 500; }
.project-connections-overview p { margin: 0; color: var(--yp-text-muted); line-height: 1.7; }
.project-connections-overview__row { display: flex; min-height: 36px; align-items: center; flex-wrap: wrap; gap: 8px; padding: 8px 0; border-bottom: 1px solid var(--yp-border-subtle); }
.project-connections-overview__row strong { font-weight: 500; color: var(--yp-text-primary); }
.project-connections-overview__row code { color: var(--yp-text-muted); font-size: 12px; }
.project-connections-overview__row a { color: var(--yp-action-primary); text-decoration: none; margin-left: auto; white-space: nowrap; }
.project-connections-overview__targets { display: flex; flex-wrap: wrap; gap: 6px; }
.unified-project-settings__empty { padding: var(--yp-space-5); border: 1px dashed var(--yp-border-default); border-radius: var(--yp-radius-sm); background: var(--yp-bg-sunken); color: var(--yp-text-secondary); line-height: 1.7; }
</style>
