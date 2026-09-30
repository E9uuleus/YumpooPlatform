<script setup lang="ts">
import { ElButton } from 'element-plus'
import { useRouter } from 'vue-router'
import StatusScene from '../../components/motion/StatusScene.vue'
import YpResultPage from '../../components/yp/YpResultPage.vue'

const router = useRouter()
</script>

<template>
  <section class="result-page">
    <yp-result-page
      eyebrow="404"
      title="页面不存在"
      description="请检查地址，或返回 YumpooPlatform 工作台。"
    >
      <template #scene>
        <status-scene variant="not-found" />
      </template>
      <template #actions>
        <el-button
          type="primary"
          @click="router.push({ name: 'workspace-entry' })"
        >
          返回工作台
        </el-button>
      </template>
    </yp-result-page>
  </section>
</template>
