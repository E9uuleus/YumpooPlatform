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
        :class="{ 'project-workspace-header__icon--catalog': section === 'catalog' }"
        aria-hidden="true"
      >
        <template v-if="section === 'catalog'">
          <span class="project-workspace-header__avatar-text">{{ heading ? heading.charAt(0) : 'M' }}</span>
          <span class="project-workspace-header__home-badge" title="主工作空间">
            <svg width="11" height="11" viewBox="0 0 16 16" fill="currentColor">
              <path d="M8.707 1.5a1 1 0 0 0-1.414 0L.646 8.146a.5.5 0 0 0 .708.708L2 8.207V13.5A1.5 1.5 0 0 0 3.5 15h9a1.5 1.5 0 0 0 1.5-1.5V8.207l.646.647a.5.5 0 0 0 .708-.708L8.707 1.5Z" />
            </svg>
          </span>
        </template>
        <el-icon v-else>
          <folder-opened />
        </el-icon>
      </div>
      <div class="project-workspace-header__copy">
        <div class="project-workspace-header__title-row">
          <h1>{{ heading }}</h1>
          <span
            v-if="section === 'catalog'"
            class="project-workspace-header__chevron"
            aria-hidden="true"
          >
            <svg width="16" height="16" viewBox="0 0 20 20" fill="currentColor">
              <path d="M5.293 7.293a1 1 0 0 1 1.414 0L10 10.586l3.293-3.293a1 1 0 1 1 1.414 1.414l-4 4a1 1 0 0 1-1.414 0l-4-4a1 1 0 0 1 0-1.414Z" />
            </svg>
          </span>
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

.project-workspace-header__icon--catalog {
  background: linear-gradient(135deg, var(--yp-status-pink) 0%, color-mix(in srgb, var(--yp-status-pink) 80%, white) 100%) !important;
  color: var(--yp-status-pink-foreground) !important;
  box-shadow: 0 4px 12px color-mix(in srgb, var(--yp-status-pink) 25%, transparent);
  font-weight: 700;
  position: relative;
}

.project-workspace-header__avatar-text {
  font-size: 38px;
  line-height: 1;
  font-weight: 700;
  color: var(--yp-status-pink-foreground);
  user-select: none;
  font-family: var(--yp-font-heading);
}

.project-workspace-header__home-badge {
  position: absolute;
  right: -3px;
  bottom: -3px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  background: var(--yp-text-primary);
  color: var(--yp-bg-surface);
  border-radius: 6px;
  border: 2px solid var(--yp-bg-surface);
  box-shadow: 0 2px 4px color-mix(in srgb, var(--yp-text-primary) 15%, transparent);
}

.project-workspace-header__chevron {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--yp-text-secondary);
  transition: transform var(--yp-motion-fast) var(--yp-ease-standard), color var(--yp-motion-fast) var(--yp-ease-standard);
  cursor: pointer;
  padding: 4px;
  border-radius: var(--yp-radius-xs);
}

.project-workspace-header__chevron:hover {
  color: var(--yp-text-primary);
  background: var(--yp-bg-hover);
}

.project-workspace-header--catalog h1 {
  font-size: 32px;
  font-weight: 700;
  line-height: 40px;
  letter-spacing: -0.02em;
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

  .project-workspace-header--catalog .project-workspace-header__avatar-text {
    font-size: 30px;
  }

  .project-workspace-header--catalog .project-workspace-header__home-badge {
    width: 18px;
    height: 18px;
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
