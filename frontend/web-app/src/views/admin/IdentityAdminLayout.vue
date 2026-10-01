<script setup lang="ts">
import YpPageHeader from '../../components/yp/YpPageHeader.vue'
</script>

<template>
  <section class="identity-admin">
    <yp-page-header
      eyebrow="公司与组织"
      title="公司管理"
      description="查看公司与企微状态、诊断通讯录同步，并管理成员账号与角色。"
    />
    <router-view />
  </section>
</template>
