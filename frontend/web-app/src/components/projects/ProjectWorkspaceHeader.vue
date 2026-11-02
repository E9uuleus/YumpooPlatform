<script setup lang="ts">
import { projectStatus } from './projectStatus'
import { Calendar, User } from '@element-plus/icons-vue'
import { ProjectActorAccess, type ProjectDetail } from '@yumpoo/api-client'
import { ElIcon, ElPopover } from 'element-plus'
import { computed, ref } from 'vue'
import { useSession } from '../../composables/useSession'
import { formatChineseTimestamp } from '../../design-system/dates'
import YpAssignee from '../yp/YpAssignee.vue'
import YpStatusTag from '../yp/YpStatusTag.vue'
import ProjectMoreMenu from './ProjectMoreMenu.vue'

type ProjectSection = 'catalog' | 'overview' | 'statistics' | 'members' | 'activity' | 'settings'

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

const session = useSession()
const heading = computed(() => props.project?.name ?? props.title)
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const infoOpen = ref(false)
const readOnlyAdmin = computed(() => props.project?.actorAccess === ProjectActorAccess.CompanyAdmin)
const emit = defineEmits<{ changed: [] }>()
</script>

<template>
  <header
    class="project-workspace-header"
    :class="{ 'project-workspace-header--catalog': section === 'catalog' }"
  >
    <div
      v-if="section === 'catalog'"
      class="project-workspace-header__identity"
    >
      <div
        class="project-workspace-header__icon project-workspace-header__icon--catalog"
        aria-hidden="true"
      >
        <span class="project-workspace-header__avatar-text">{{ heading ? heading.charAt(0) : 'M' }}</span>
        <span
          class="project-workspace-header__home-badge"
          title="主工作空间"
        >
          <svg
            width="11"
            height="11"
            viewBox="0 0 16 16"
            fill="currentColor"
          >
            <path d="M8.707 1.5a1 1 0 0 0-1.414 0L.646 8.146a.5.5 0 0 0 .708.708L2 8.207V13.5A1.5 1.5 0 0 0 3.5 15h9a1.5 1.5 0 0 0 1.5-1.5V8.207l.646.647a.5.5 0 0 0 .708-.708L8.707 1.5Z" />
          </svg>
        </span>
      </div>
      <div class="project-workspace-header__copy">
        <div class="project-workspace-header__title-row">
          <h1>{{ heading }}</h1>
          <span
            class="project-workspace-header__chevron"
            aria-hidden="true"
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 20 20"
              fill="currentColor"
            >
              <path d="M5.293 7.293a1 1 0 0 1 1.414 0L10 10.586l3.293-3.293a1 1 0 1 1 1.414 1.414l-4 4a1 1 0 0 1-1.414 0l-4-4a1 1 0 0 1 0-1.414Z" />
            </svg>
          </span>
        </div>
        <p v-if="description">
          {{ description }}
        </p>
      </div>
    </div>
    <div
      v-else
      class="project-workspace-header__heading"
    >
      <h1
        class="project-workspace-header__title"
        data-tour="project-title"
      >
        <el-popover
          v-model:visible="infoOpen"
          role="dialog"
          trigger="click"
          placement="bottom-start"
          :width="320"
          :offset="6"
          :show-arrow="false"
          :persistent="false"
          :disabled="!project"
          popper-class="project-info-popover"
        >
          <template #reference>
            <button
              type="button"
              class="project-workspace-header__title-trigger"
              aria-haspopup="dialog"
              :aria-expanded="infoOpen"
              :disabled="!project"
            >
              <span class="project-workspace-header__title-text">{{ heading }}</span>
              <svg
                class="project-workspace-header__title-chevron"
                :class="{ 'is-open': infoOpen }"
                width="20"
                height="20"
                viewBox="0 0 20 20"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="M6 8l4 4 4-4"
                  stroke="currentColor"
                  stroke-width="1.6"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
          </template>
          <div
            v-if="project"
            class="project-info"
          >
            <dl class="project-info__facts">
              <div class="project-info__row">
                <dt>
                  <el-icon aria-hidden="true">
                    <user />
                  </el-icon>
                  负责人
                </dt>
                <dd>
                  <yp-assignee
                    :user-id="project.ownerUserId"
                    :display-name="project.ownerDisplayName"
                    size="table"
                  />
                </dd>
              </div>
              <div class="project-info__row">
                <dt>
                  <el-icon aria-hidden="true">
                    <calendar />
                  </el-icon>
                  创建时间
                </dt>
                <dd>{{ formatChineseTimestamp(project.createdAt, timezone) }}</dd>
              </div>
            </dl>
            <p
              v-if="readOnlyAdmin"
              class="project-info__note"
            >
              你正以企业管理员身份只读查看此项目，加入项目后可设置通知提醒。
            </p>
          </div>
        </el-popover>
      </h1>
      <yp-status-tag
        v-if="project && projectStatus(project) !== 'ACTIVE'"
        domain="project-lifecycle"
        :status="projectStatus(project)"
        size="small"
      />
    </div>
    <div
      v-if="project || $slots['primary-action']"
      class="project-workspace-header__actions"
    >
      <slot name="primary-action" />
      <project-more-menu
        v-if="project && section !== 'catalog'"
        :project="project"
        :section="section"
        @changed="emit('changed')"
      />
    </div>
  </header>
</template>

<style scoped>
.project-workspace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--yp-space-6);
  min-height: 48px;
  padding: var(--yp-space-2) 0 var(--yp-space-4);
}

.project-workspace-header__heading {
  display: flex;
  flex: 1 1 0%;
  align-items: center;
  gap: var(--yp-space-2);
  min-width: 0;
}

.project-workspace-header__title {
  display: flex;
  min-width: 0;
  max-width: 100%;
  margin: 0 0 0 calc(0px - var(--yp-space-2));
  font-size: inherit;
}

.project-workspace-header__title-trigger {
  display: flex;
  align-items: center;
  gap: var(--yp-space-1);
  width: max-content;
  max-width: 100%;
  min-width: 0;
  height: 40px;
  padding: 0 var(--yp-space-2);
  border: 0;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-primary);
  background: transparent;
  font: 600 24px / 32px var(--yp-font-heading);
  letter-spacing: -0.01em;
  cursor: pointer;
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard);
}

.project-workspace-header__title-trigger:hover:not(:disabled),
.project-workspace-header__title-trigger[aria-expanded='true'] {
  background: var(--yp-bg-hover);
}

.project-workspace-header__title-trigger:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 1px;
}

.project-workspace-header__title-trigger:disabled {
  cursor: default;
}

.project-workspace-header__title-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-workspace-header__title-chevron {
  flex: none;
  color: var(--yp-text-secondary);
  transition: transform var(--yp-motion-popover) var(--yp-ease-standard);
}

.project-workspace-header__title-chevron.is-open {
  transform: rotate(180deg);
}

:global(.project-info-popover.el-popover.el-popper) {
  padding: var(--yp-space-2);
}

.project-info__facts {
  display: grid;
  margin: 0;
}

.project-info__row {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  align-items: center;
  gap: var(--yp-space-2);
  min-height: 40px;
  padding: 0 var(--yp-space-2);
}

.project-info__row dt {
  display: inline-flex;
  align-items: center;
  gap: var(--yp-space-2);
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-body-size);
}

.project-info__row dd {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--yp-text-primary);
  font-size: var(--yp-type-body-size);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-info__note {
  margin: 0;
  padding: var(--yp-space-2);
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  line-height: var(--yp-type-caption-line);
}

.project-workspace-header__identity,
.project-workspace-header__title-row,
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

.project-workspace-header__copy h1,
.project-workspace-header__copy p {
  margin: 0;
}

.project-workspace-header__copy h1 {
  overflow: hidden;
  color: var(--yp-text-primary);
  font-size: 30px;
  font-weight: 550;
  line-height: 38px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-workspace-header__copy p {
  margin-top: var(--yp-space-1);
  color: var(--yp-text-secondary);
}

.project-workspace-header__actions {
  flex: 0 0 auto;
  gap: var(--yp-space-2);
}

.project-workspace-header--catalog {
  position: relative;
  align-items: flex-start;
  margin-top: calc(16px - var(--yp-space-5));
  padding: 0 0 var(--yp-space-4);
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
  background: var(--yp-status-pink) !important;
  color: var(--yp-status-pink-foreground) !important;
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
    align-items: flex-start;
    gap: var(--yp-space-3);
  }

  .project-workspace-header__actions {
    flex-wrap: wrap;
    justify-content: flex-start;
  }

  .project-workspace-header__heading {
    flex: 0 1 auto;
    width: 100%;
  }

  .project-workspace-header__title-trigger {
    font-size: 20px;
    line-height: 28px;
  }
}
</style>
