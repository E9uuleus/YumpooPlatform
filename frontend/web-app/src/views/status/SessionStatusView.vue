<script setup lang="ts">
import { ElButton } from 'element-plus'
import { computed } from 'vue'
import InlineProblem from '../../components/InlineProblem.vue'
import StatusScene from '../../components/motion/StatusScene.vue'
import YpResultPage from '../../components/yp/YpResultPage.vue'
import { useSession } from '../../composables/useSession'
import type { StatusSceneVariant } from '../../motion/scenes/statusScenes'

const session = useSession()

const content = computed<{ scene: StatusSceneVariant, title: string, description: string, retry: boolean }>(() => {
  if (session.phase.value === 'accountDisabled') {
    return {
      scene: 'forbidden',
      title: '账号当前不可用',
      description: '账号已停用或成员已离职，请联系公司管理员处理。',
      retry: false,
    }
  }
  if (session.phase.value === 'upgradeRequired') {
    return {
      scene: 'upgrade',
      title: '客户端需要升级',
      description: '当前客户端版本不再受支持，请联系管理员获取升级安排。',
      retry: false,
    }
  }
  return {
    scene: 'offline',
    title: '暂时无法进入 Yumpoo',
    description: '服务或网络暂时不可用，可以稍后重新检查。',
    retry: true,
  }
})
</script>

<template>
  <main class="status-page">
    <yp-result-page
      :title="content.title"
      :description="content.description"
    >
      <template #scene>
        <status-scene
          :key="content.scene"
          :variant="content.scene"
        />
      </template>
      <template
        v-if="session.blockingProblem.value"
        #extra
      >
        <inline-problem
          class="status-problem"
          :problem="session.blockingProblem.value"
        />
      </template>
      <template
        v-if="content.retry"
        #actions
      >
        <el-button
          type="primary"
          @click="session.ensureAuthentication(true)"
        >
          重新检查
        </el-button>
      </template>
    </yp-result-page>
  </main>
</template>
