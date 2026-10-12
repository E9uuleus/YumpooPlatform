<script setup lang="ts">
import YpPageHeader from '../../components/yp/YpPageHeader.vue'
</script>

<template>
  <section class="identity-admin">
    <yp-page-header
      eyebrow="公司与组织"
      title="公司管理"
      description="总览全公司项目与活动，查看公司与企微状态，管理通讯录同步、定时任务以及成员账号与角色。"
    />
    <router-view />
  </section>
</template>
