<script setup lang="ts">
import { ProjectActorAccess } from '@yumpoo/api-client'
import ArchivedProjectTable from '../../components/projects/ArchivedProjectTable.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'

const OWNED = [ProjectActorAccess.Owner]
</script>

<template>
  <section class="project-catalog archived-projects">
    <project-workspace-header
      section="catalog"
      title="归档项目"
      description="仅展示你负责的归档项目。"
    />
    <archived-project-table :actor-accesses="OWNED" />
  </section>
</template>
