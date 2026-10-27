<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { ElDialog, ElIcon, ElInput } from 'element-plus'
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { docPath, loadSearchIndex, searchDocs, type DocSearchEntry } from './docsNav'

const open = defineModel<boolean>({ required: true })
const router = useRouter()
const query = ref('')
const index = ref<DocSearchEntry[]>()
const selected = ref(0)
const input = ref<InstanceType<typeof ElInput>>()
const list = ref<HTMLElement>()

const results = computed(() => index.value ? searchDocs(index.value, query.value) : [])
watch(results, () => { selected.value = 0 })
watch(open, value => {
  if (!value) return
  void loadSearchIndex().then(entries => { index.value = entries })
  void nextTick(() => input.value?.focus())
}, { immediate: true })

function go(position: number): void {
  const hit = results.value[position]
  if (!hit) return
  open.value = false
  query.value = ''
  void router.push(docPath(hit.slug, hit.id || undefined))
}

function move(delta: 1 | -1): void {
  if (!results.value.length) return
  selected.value = (selected.value + delta + results.value.length) % results.value.length
  void nextTick(() => list.value?.querySelector('.is-selected')?.scrollIntoView({ block: 'nearest' }))
}
</script>

<template>
  <el-dialog
    v-model="open"
    class="docs-search"
    width="min(640px, calc(100vw - 32px))"
    :show-close="false"
    align-center
    append-to-body
    aria-label="搜索文档"
  >
    <template #header>
      <el-input
        ref="input"
        v-model="query"
        size="large"
        placeholder="搜索文档，例如 并行计时、工作日历"
        aria-label="搜索文档"
        @keydown.down.prevent="move(1)"
        @keydown.up.prevent="move(-1)"
        @keydown.enter.prevent="go(selected)"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
    </template>
    <ul
      v-if="results.length"
      ref="list"
      class="docs-search__results"
      role="listbox"
      aria-label="搜索结果"
    >
      <li
        v-for="(hit, position) in results"
        :key="`${hit.slug}#${hit.id}`"
        role="option"
        :aria-selected="position === selected"
        :class="{ 'is-selected': position === selected }"
        @mousemove="selected = position"
        @click="go(position)"
      >
        <p class="docs-search__path">
          <span>{{ hit.title }}</span>
          <span
            v-if="hit.id"
            class="docs-search__heading"
          >{{ hit.heading }}</span>
        </p>
        <p class="docs-search__snippet">
          <template
            v-for="(part, partIndex) in hit.snippet"
            :key="partIndex"
          >
            <mark v-if="part.hit">{{ part.text }}</mark>
            <span v-else>{{ part.text }}</span>
          </template>
        </p>
      </li>
    </ul>
    <p
      v-else-if="query.trim() && index"
      class="docs-search__empty"
    >
      没有找到相关内容
    </p>
    <p
      v-else
      class="docs-search__hint"
    >
      输入关键词搜索全部文档，↑↓ 选择，Enter 打开，Esc 关闭。
    </p>
  </el-dialog>
</template>
