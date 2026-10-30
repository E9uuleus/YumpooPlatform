<script setup lang="ts">
import YpNotice from './yp/YpNotice.vue'
import { ElButton } from 'element-plus'
import { computed, ref, watch } from 'vue'
import {
  problemDiagnostics,
  problemFieldErrors,
  problemMessage,
  problemRequestId,
  type ApiProblem,
} from '../api/problems'

const props = defineProps<{
  problem: ApiProblem
  title?: string
  /** 把字段路径换成用户看得懂的位置，例如导入的「第 12 行 · 备注」；省略时显示字段路径。 */
  fieldLabel?: (field: string) => string
}>()

const copied = ref(false)
const expanded = ref(false)
/** 问题出现的时刻，诊断信息据此在运行日志中定位。 */
const occurredAt = ref(new Date())
const message = computed(() => problemMessage(props.problem))
const requestId = computed(() => problemRequestId(props.problem))
const fieldErrors = computed(() => problemFieldErrors(props.problem)
  .map(error => ({ ...error, label: props.fieldLabel?.(error.field) ?? error.field })))
const diagnostics = computed(() => problemDiagnostics(props.problem, occurredAt.value, props.fieldLabel))
const retryAfter = computed(() => props.problem.kind === 'response'
  ? props.problem.retryAfter
  : undefined)

watch(() => props.problem, () => {
  occurredAt.value = new Date()
  copied.value = false
  expanded.value = false
})

async function copyDiagnostics(): Promise<void> {
  try {
    await navigator.clipboard.writeText(diagnostics.value)
    copied.value = true
  } catch {
    copied.value = false
  }
}
</script>

<template>
  <yp-notice
    class="inline-error"
    tone="danger"
    :title="title"
  >
    <p class="problem-message">
      {{ message }}
    </p>
    <ul
      v-if="fieldErrors.length"
      class="problem-fields"
    >
      <li
        v-for="error in fieldErrors"
        :key="`${error.field}:${error.code}`"
      >
        <strong>{{ error.label }}</strong>：{{ error.message }}
      </li>
    </ul>
    <slot />
    <p
      v-if="retryAfter"
      class="problem-retry"
    >
      建议等待 {{ retryAfter }} 秒后重试。
    </p>
    <div
      v-if="requestId"
      class="problem-meta"
    >
      <span>请求 ID：<code>{{ requestId }}</code></span>
      <el-button
        link
        type="primary"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        {{ expanded ? '收起详情' : '查看详情' }}
      </el-button>
      <el-button
        link
        type="primary"
        @click="copyDiagnostics"
      >
        {{ copied ? '已复制' : '复制诊断信息' }}
      </el-button>
    </div>
    <pre
      v-if="expanded"
      class="problem-diagnostics"
    >{{ diagnostics }}</pre>
  </yp-notice>
</template>
