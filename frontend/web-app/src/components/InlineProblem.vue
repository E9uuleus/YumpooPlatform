<script setup lang="ts">
import { ElAlert, ElButton } from 'element-plus'
import { computed, ref } from 'vue'
import {
  problemMessage,
  problemRequestId,
  type ApiProblem,
} from '../api/problems'

const props = defineProps<{
  problem: ApiProblem
  title?: string
}>()

const copied = ref(false)
const message = computed(() => problemMessage(props.problem))
const requestId = computed(() => problemRequestId(props.problem))
const retryAfter = computed(() => props.problem.kind === 'response'
  ? props.problem.retryAfter
  : undefined)

async function copyRequestId(): Promise<void> {
  if (!requestId.value || !navigator.clipboard) {
    return
  }
  try {
    await navigator.clipboard.writeText(requestId.value)
    copied.value = true
  } catch {
    copied.value = false
  }
}
</script>

<template>
  <el-alert
    class="inline-error"
    type="error"
    :closable="false"
    :title="title ?? message"
    show-icon
  >
    <p
      v-if="title"
      class="problem-message"
    >
      {{ message }}
    </p>
    <div
      v-if="requestId"
      class="problem-meta"
    >
      <span>requestId: <code>{{ requestId }}</code></span>
      <el-button
        link
        type="primary"
        @click="copyRequestId"
      >
        {{ copied ? '已复制' : '复制' }}
      </el-button>
    </div>
    <p
      v-if="retryAfter"
      class="problem-retry"
    >
      建议等待 {{ retryAfter }} 秒后重试。
    </p>
  </el-alert>
</template>
