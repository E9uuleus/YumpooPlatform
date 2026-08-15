<script setup lang="ts">
import { ElButton, ElResult } from 'element-plus'
import { computed } from 'vue'
import { useSession } from '../composables/useSession'
import InlineProblem from '../components/InlineProblem.vue'

const session = useSession()

const content = computed(() => {
  if (session.phase.value === 'accountDisabled') {
    return {
      icon: 'warning' as const,
      title: '账号当前不可用',
      description: '账号已停用或成员已离职，请联系企业管理员处理。',
      retry: false,
    }
  }
  if (session.phase.value === 'upgradeRequired') {
    return {
      icon: 'warning' as const,
      title: '客户端需要升级',
      description: '当前客户端版本不再受支持，请联系管理员获取升级安排。',
      retry: false,
    }
  }
  return {
    icon: 'error' as const,
    title: '暂时无法进入 Yumpoo',
    description: '服务或网络暂时不可用，可以稍后重新检查。',
    retry: true,
  }
})
</script>

<template>
  <main class="status-page">
    <el-result
      :icon="content.icon"
      :title="content.title"
      :sub-title="content.description"
    >
      <template #extra>
        <inline-problem
          v-if="session.blockingProblem.value"
          class="status-problem"
          :problem="session.blockingProblem.value"
        />
        <el-button
          v-if="content.retry"
          type="primary"
          @click="session.ensureAuthentication(true)"
        >
          重新检查
        </el-button>
      </template>
    </el-result>
  </main>
</template>
