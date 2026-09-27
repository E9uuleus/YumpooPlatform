<script setup lang="ts">
import YpPageHeader from '../../components/yp/YpPageHeader.vue'
</script>

<template>
  <section class="identity-admin">
    <yp-page-header
      eyebrow="公司与组织"
      title="公司管理"
    >
      <template #meta>
        <p class="identity-admin__lead">
          查看公司与企微状态、诊断通讯录同步，并管理成员账号与角色。
        </p>
      </template>
    </yp-page-header>
    <router-view />
  </section>
</template>

<style scoped>
.identity-admin__lead {
  max-width: 64ch;
  margin: 0;
  color: var(--yp-text-secondary);
}
</style>
