<script setup lang="ts">
import { Box, Document, FolderOpened, MoreFilled, User } from '@element-plus/icons-vue'
import type { ProjectDetail } from '@yumpoo/api-client'
import {
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElIcon,
  ElTooltip,
} from 'element-plus'
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import YpAssignee from '../yp/YpAssignee.vue'
import YpStatusTag from '../yp/YpStatusTag.vue'

type ProjectSection = 'catalog' | 'overview' | 'contents' | 'members' | 'products' | 'settings'

const props = withDefaults(defineProps<{
  section: ProjectSection
  title?: string
  description?: string
  project?: ProjectDetail | undefined
}>(), {
  title: '项目',
  description: '',
  project: undefined,
})

const router = useRouter()
const projectId = computed(() => props.project?.id)
const heading = computed(() => props.project?.name ?? props.title)
const supportingText = computed(() => props.project?.description
  || (props.project ? `${props.project.workspaceName} 中的项目空间` : props.description))
const moreRoutes = computed(() => [
  ...(props.section !== 'overview' ? [{ command: 'project-overview', label: '概览' }] : []),
  ...(props.section !== 'contents' ? [{ command: 'project-contents', label: 'Content' }] : []),
  ...(props.section !== 'products' ? [{ command: 'project-products', label: '关联产品' }] : []),
  ...(props.section !== 'settings' ? [{ command: 'project-settings', label: '设置' }] : []),
])

function navigate(routeName: string): void {
  if (!projectId.value) return
  void router.push({ name: routeName, params: { projectId: projectId.value } })
}
</script>

<template>
  <header
    class="project-workspace-header"
    :class="{ 'project-workspace-header--catalog': section === 'catalog' }"
  >
    <div class="project-workspace-header__identity">
      <div
        class="project-workspace-header__icon"
        aria-hidden="true"
      >
        <el-icon>
          <folder-opened />
        </el-icon>
      </div>
      <div class="project-workspace-header__copy">
        <div class="project-workspace-header__title-row">
          <h1>{{ heading }}</h1>
          <span
            v-if="project"
            class="project-workspace-header__code"
          >{{ project.code }}</span>
        </div>
        <p v-if="supportingText">
          {{ supportingText }}
        </p>
        <div
          v-if="project"
          class="project-workspace-header__meta"
        >
          <yp-status-tag
            domain="project-lifecycle"
            :status="project.lifecycle"
            effect="soft"
          />
          <yp-assignee
            :user-id="project.ownerUserId"
            :display-name="project.ownerDisplayName"
            size="table"
          />
        </div>
      </div>
    </div>
    <div
      v-if="project || $slots['primary-action']"
      class="project-workspace-header__actions"
    >
      <el-button
        v-if="project && section !== 'contents'"
        @click="navigate('project-contents')"
      >
        <el-icon aria-hidden="true">
          <document />
        </el-icon>
        Content
      </el-button>
      <el-button
        v-if="project && section !== 'members'"
        @click="navigate('project-members')"
      >
        <el-icon aria-hidden="true">
          <user />
        </el-icon>
        成员
      </el-button>
      <el-button
        v-if="project && section !== 'products'"
        @click="navigate('project-products')"
      >
        <el-icon aria-hidden="true">
          <box />
        </el-icon>
        产品
      </el-button>
      <slot name="primary-action" />
      <el-tooltip
        v-if="project && moreRoutes.length"
        content="更多"
        placement="top"
      >
        <el-dropdown
          trigger="click"
          @command="navigate"
        >
          <el-button
            class="project-workspace-header__more"
            aria-label="更多项目操作"
          >
            <el-icon aria-hidden="true">
              <more-filled />
            </el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="item in moreRoutes"
                :key="item.command"
                :command="item.command"
              >
                {{ item.label }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-tooltip>
    </div>
  </header>
</template>

<style scoped>
.project-workspace-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--yp-space-6);
  padding: var(--yp-space-6);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-surface);
}

.project-workspace-header__identity,
.project-workspace-header__title-row,
.project-workspace-header__meta,
.project-workspace-header__actions {
  display: flex;
  align-items: center;
}

.project-workspace-header__identity {
  min-width: 0;
  gap: var(--yp-space-5);
}

.project-workspace-header__icon {
  display: grid;
  width: 64px;
  height: 64px;
  flex: 0 0 64px;
  place-items: center;
  border-radius: var(--yp-radius-md);
  color: var(--yp-link);
  background: var(--yp-bg-selected);
  font-size: 28px;
}

.project-workspace-header__copy {
  min-width: 0;
}

.project-workspace-header__title-row {
  min-width: 0;
  gap: var(--yp-space-3);
}

h1,
p {
  margin: 0;
}

h1 {
  overflow: hidden;
  color: var(--yp-text-primary);
  font-size: 30px;
  font-weight: 550;
  line-height: 38px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

p {
  margin-top: var(--yp-space-1);
  color: var(--yp-text-secondary);
}

.project-workspace-header__code {
  padding: 2px var(--yp-space-2);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-muted);
  background: var(--yp-bg-sunken);
  font-size: var(--yp-type-caption-size);
  white-space: nowrap;
}

.project-workspace-header__meta {
  flex-wrap: wrap;
  gap: var(--yp-space-3);
  margin-top: var(--yp-space-3);
}

.project-workspace-header__actions {
  flex: 0 0 auto;
  gap: var(--yp-space-2);
}

.project-workspace-header__more {
  width: var(--yp-control-height);
  padding: 0;
}

.project-workspace-header--catalog {
  position: relative;
  margin-top: calc(16px - var(--yp-space-5));
  padding: 0 0 var(--yp-space-4);
  border: 0;
  border-radius: 0;
  background: transparent;
}

.project-workspace-header--catalog .project-workspace-header__identity {
  min-height: 64px;
  padding-left: 100px;
  align-items: flex-start;
}

.project-workspace-header--catalog .project-workspace-header__icon {
  position: absolute;
  top: -36px;
  left: 0;
  width: 80px;
  height: 80px;
  flex-basis: 80px;
  border-radius: var(--yp-radius-xl);
  color: var(--yp-link);
  background: var(--yp-bg-selected);
  box-shadow: none;
  font-size: 36px;
}

.project-workspace-header--catalog h1 {
  font-size: 32px;
  font-weight: 700;
  line-height: 40px;
}

@media (max-width: 959.98px) {
  .project-workspace-header--catalog {
    margin-top: calc(10px - var(--yp-space-5));
    padding: 0 0 var(--yp-space-3);
  }

  .project-workspace-header--catalog .project-workspace-header__identity {
    min-height: 58px;
    padding-left: 80px;
  }

  .project-workspace-header--catalog .project-workspace-header__icon {
    top: -26px;
    width: 64px;
    height: 64px;
    flex-basis: 64px;
    font-size: 28px;
  }

  .project-workspace-header--catalog h1 {
    font-size: 28px;
    line-height: 36px;
  }
}

@media (max-width: 720px) {
  .project-workspace-header {
    flex-direction: column;
  }

  .project-workspace-header:not(.project-workspace-header--catalog) {
    padding: var(--yp-space-4);
  }

  .project-workspace-header__icon {
    width: 52px;
    height: 52px;
    flex-basis: 52px;
    font-size: 24px;
  }

  h1 {
    font-size: 24px;
    line-height: 32px;
  }

}
</style>
