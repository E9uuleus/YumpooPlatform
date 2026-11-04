<script setup lang="ts">
import type { ProjectSummary } from '@yumpoo/api-client'
import { ElPopover } from 'element-plus'
import { ref } from 'vue'
import ProjectPinButton from '../projects/ProjectPinButton.vue'

/** 菜单「…」：在侧边弹出菜单之外的下一批项目，可打开或置顶。 */
defineProps<{
  projects: ProjectSummary[]
  pinnedIds: ReadonlySet<string>
  placement: 'right-start' | 'bottom-start'
}>()
const emit = defineEmits<{
  open: [project: ProjectSummary]
  togglePin: [project: ProjectSummary]
}>()
const visible = ref(false)

function open(project: ProjectSummary): void {
  visible.value = false
  emit('open', project)
}
</script>

<template>
  <el-popover
    v-model:visible="visible"
    trigger="click"
    :placement="placement"
    :width="260"
    :offset="12"
    popper-class="project-navigation-more-popper"
  >
    <template #reference>
      <button
        class="project-navigation__more"
        type="button"
        aria-label="更多项目"
        aria-haspopup="dialog"
        :aria-expanded="visible"
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 20 20"
          fill="currentColor"
          aria-hidden="true"
        >
          <circle
            cx="4"
            cy="10"
            r="1.5"
          />
          <circle
            cx="10"
            cy="10"
            r="1.5"
          />
          <circle
            cx="16"
            cy="10"
            r="1.5"
          />
        </svg>
      </button>
    </template>
    <div
      class="project-navigation-more"
      role="dialog"
      aria-label="更多项目"
    >
      <div class="project-navigation-more__title">
        更多项目
      </div>
      <div
        v-for="project in projects"
        :key="project.id"
        class="project-navigation-more__item"
      >
        <button
          type="button"
          class="project-navigation-more__project"
          @click="open(project)"
        >
          <svg
            width="16"
            height="16"
            viewBox="0 0 20 20"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M2.75 5.5c0-.966.784-1.75 1.75-1.75h3.19c.464 0 .91.184 1.238.513L10.165 5.5H15.5c.966 0 1.75.784 1.75 1.75v7.25a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-9Z"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linejoin="round"
            />
          </svg>
          <span>{{ project.name }}</span>
        </button>
        <project-pin-button
          :pinned="pinnedIds.has(project.id)"
          :name="project.name"
          @toggle="emit('togglePin', project)"
        />
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
:global(.project-navigation-more-popper.el-popover.el-popper) {
  padding: 6px;
}

.project-navigation-more {
  display: grid;
  gap: 2px;
}

.project-navigation-more__title {
  padding: 4px 8px 6px;
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  font-weight: 600;
}

.project-navigation-more__item {
  display: flex;
  align-items: center;
  gap: 2px;
  padding-right: 4px;
  border-radius: var(--yp-radius-md);
}

.project-navigation-more__item:hover {
  background: var(--yp-bg-sunken);
}

.project-navigation-more__project {
  display: flex;
  flex: 1;
  min-width: 0;
  height: 34px;
  align-items: center;
  gap: var(--yp-space-3);
  padding: 0 var(--yp-space-3);
  border: 0;
  color: var(--yp-text-secondary);
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.project-navigation-more__item:hover .project-navigation-more__project {
  color: var(--yp-text-primary);
}

.project-navigation-more__project svg {
  flex: 0 0 16px;
}

.project-navigation-more__project span {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (hover: hover) {
  .project-navigation-more__item .project-pin-button:not(.project-pin-button--active) {
    opacity: 0;
  }

  .project-navigation-more__item:is(:hover, :focus-within) .project-pin-button {
    opacity: 1;
  }
}
</style>
