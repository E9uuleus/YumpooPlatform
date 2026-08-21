<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
function change(name: string): void { void router.push({ name, params: route.params }) }
</script>

<template>
  <section class="project-page">
    <el-button link @click="$router.push({ name: 'projects' })">← 返回项目目录</el-button>
    <nav class="project-tabs"><button type="button" :class="{ active: route.name === 'project-overview' }" @click="change('project-overview')">概览</button><button type="button" :class="{ active: route.name === 'project-members' }" @click="change('project-members')">成员</button><button type="button" :class="{ active: route.name === 'project-settings' }" @click="change('project-settings')">设置</button></nav>
    <router-view />
  </section>
</template>
