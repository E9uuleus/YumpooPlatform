<script setup lang="ts">
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpPageHeader from '../../components/yp/YpPageHeader.vue'
import { useIdentityAdmin } from '../../composables/useIdentityAdmin'

const { authentication } = useIdentityAdmin()
</script>

<template>
  <section class="identity-admin">
    <yp-page-header
      eyebrow="身份与组织"
      title="身份管理"
      description="查看公司与企微状态、诊断通讯录同步，并管理成员账号。"
    >
      <template #meta>
        <yp-assignee
          v-if="authentication"
          :user-id="authentication.user.id"
          :display-name="authentication.user.displayName"
          size="table"
        />
      </template>
    </yp-page-header>
    <router-view />
  </section>
</template>
