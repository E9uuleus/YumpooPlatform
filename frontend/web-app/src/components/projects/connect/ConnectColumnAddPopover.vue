<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElIcon, ElInput } from 'element-plus'
import { Connection, Hide, Search } from '@element-plus/icons-vue'

const props = defineProps<{ canManage: boolean; hiddenColumns: { key: string; label: string }[] }>()
const emit = defineEmits<{ addConnectColumn: []; showColumn: [key: string] }>()
const query = ref('')
const input = ref<InstanceType<typeof ElInput>>()
const matches = (text: string) => text.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())
const showConnect = computed(() => matches('连接项目 把工作项连接到其他项目'))
const hidden = computed(() => props.hiddenColumns.filter(column => matches(column.label)))
onMounted(() => { void nextTick(() => input.value?.focus()) })
</script>

<template>
  <div class="connect-column-center">
    <el-input
      ref="input"
      v-model="query"
      :prefix-icon="Search"
      placeholder="搜索列类型"
      aria-label="搜索列类型"
      clearable
    />
    <template v-if="showConnect">
      <p>添加列</p>
      <span :title="canManage ? undefined : '仅项目成员可在进行中的项目添加列'">
        <button
          class="connect-column-type"
          type="button"
          :disabled="!canManage"
          @click="emit('addConnectColumn')"
        >
          <el-icon class="connect-column-type__icon"><connection /></el-icon>
          <span><strong>连接项目</strong><small>把工作项连接到其他项目</small></span>
        </button>
      </span>
    </template>
    <template v-if="hidden.length">
      <p>已隐藏的列</p>
      <div class="connect-column-center__hidden">
        <button
          v-for="column in hidden"
          :key="column.key"
          type="button"
          @click="emit('showColumn', column.key)"
        >
          <el-icon><hide /></el-icon><span>{{ column.label }}</span>
        </button>
      </div>
    </template>
    <p
      v-if="!showConnect && !hidden.length"
      role="status"
    >
      没有匹配的列类型
    </p>
  </div>
</template>

<style scoped>
.connect-column-center { display: grid; gap: 8px; }
.connect-column-center p { margin: 4px 0 0; color: var(--yp-text-muted); font-size: 12px; }
.connect-column-type, .connect-column-center__hidden button { display: flex; width: 100%; min-height: 40px; align-items: center; gap: 10px; padding: 6px 8px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-primary); text-align: left; cursor: pointer; }
.connect-column-type:hover:not(:disabled), .connect-column-center__hidden button:hover { background: var(--yp-bg-hover); }
.connect-column-type:disabled { opacity: .5; cursor: not-allowed; }
.connect-column-type__icon { width: 24px; height: 24px; flex-shrink: 0; border-radius: 6px; background: var(--yp-label-sunset); color: var(--yp-text-inverse); }
.connect-column-type strong { display: block; font-size: 13px; font-weight: 500; }
.connect-column-type small { color: var(--yp-text-secondary); font-size: 12px; }
.connect-column-center__hidden { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.connect-column-center__hidden button { min-width: 0; font-size: 13px; }
.connect-column-center__hidden span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
