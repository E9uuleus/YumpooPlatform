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
      eyebrow="403"
      title="无权访问此功能"
      description="当前账号没有进入该管理区域所需的角色。"
    >
      <template #scene>
        <status-scene variant="forbidden" />
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
