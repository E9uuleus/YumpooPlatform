<script setup lang="ts">
import { ArrowRight, FolderOpened, Grid, Menu as MenuIcon, User } from '@element-plus/icons-vue'
import { reactive, ref } from 'vue'
import YpAssignee from '../components/yp/YpAssignee.vue'
import YpEmptyState from '../components/yp/YpEmptyState.vue'
import YpFilterBar from '../components/yp/YpFilterBar.vue'
import YpStatusTag from '../components/yp/YpStatusTag.vue'
import YpThemeSwitcher from '../components/yp/YpThemeSwitcher.vue'
import { useAppearance } from '../composables/useAppearance'

const appearance = useAppearance()
const drawerOpen = ref(false)
const mobileNavigationOpen = ref(false)
const contextNavigationOpen = ref(typeof window === 'undefined'
  ? true
  : (window.matchMedia?.('(min-width: 1280px)').matches ?? true))
const project = reactive({ name: 'Yumpoo Web 视觉迁移', owner: 'user-visual', type: 'PRODUCT_DEVELOPMENT' })
const rows = [
  { id: 1, code: 'YP-WEB', name: '统一项目工作台', owner: '林晓', status: 'ACTIVE', type: '产品研发', access: '负责人', priority: 'HIGH', progress: 72 },
  { id: 2, code: 'YP-NIGHT', name: '夜间主题验收', owner: '周遥', status: 'DRAFT', type: '产品研发', access: '成员', priority: 'MEDIUM', progress: 38 },
  { id: 3, code: 'YP-OPS', name: '未知总量任务', owner: '陈屿', status: 'ARCHIVED', type: '运维保障', access: '成员', priority: 'LOW', progress: null },
]
const previewGroups = [
  { id: 'product', code: 'PRODUCT', name: '产品研发 Workspace', tone: 'blue', active: 1, draft: 1, archived: 0, rows: rows.slice(0, 2) },
  { id: 'delivery', code: 'DELIVERY', name: '交付与运营 Workspace', tone: 'purple', active: 0, draft: 0, archived: 1, rows: rows.slice(2) },
]
const collapsedPreviewGroups = ref<ReadonlySet<string>>(new Set())

function togglePreviewGroup(id: string): void {
  const next = new Set(collapsedPreviewGroups.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  collapsedPreviewGroups.value = next
}
</script>

<template>
  <div
    class="acceptance-shell app-shell--projects"
    :class="{ 'acceptance-shell--context-open': contextNavigationOpen }"
  >
    <aside
      class="acceptance-modules"
      aria-label="视觉验收模块栏"
    >
      <strong>Y</strong>
      <button
        class="active"
        type="button"
        aria-label="工作台"
      >
        <el-icon aria-hidden="true">
          <grid />
        </el-icon>
      </button>
      <button
        type="button"
        aria-label="项目"
      >
        <el-icon aria-hidden="true">
          <folder-opened />
        </el-icon>
      </button>
      <button
        type="button"
        aria-label="身份管理"
      >
        <el-icon aria-hidden="true">
          <user />
        </el-icon>
      </button>
    </aside>
    <header class="acceptance-topbar">
      <div class="acceptance-topbar__context">
        <el-button
          class="acceptance-context-toggle"
          aria-label="切换上下文导航"
          :aria-expanded="contextNavigationOpen"
          @click="contextNavigationOpen = !contextNavigationOpen"
        >
          <el-icon aria-hidden="true">
            <menu-icon />
          </el-icon>
        </el-button>
        <b>Yumpoo 视觉语言验收</b>
      </div>
      <yp-theme-switcher
        :theme="appearance.themeMode.value"
        :density="appearance.densityMode.value"
        @update:theme="appearance.setThemeMode"
        @update:density="appearance.setDensityMode"
      />
    </header>
    <aside
      class="acceptance-context"
      :aria-hidden="!contextNavigationOpen"
      :inert="!contextNavigationOpen"
    >
      <small>当前模块</small>
      <h2>项目空间</h2>
      <button
        class="active"
        type="button"
      >
        项目概览
      </button>
      <button type="button">
        项目成员
      </button>
      <button type="button">
        项目设置
      </button>
    </aside>
    <main class="acceptance-content">
      <header class="acceptance-project-header">
        <div class="acceptance-project-header__identity">
          <div
            class="acceptance-project-header__icon"
            aria-hidden="true"
          >
            <el-icon>
              <folder-opened />
            </el-icon>
          </div>
          <div>
            <h1>项目中心</h1>
            <p>按 Workspace 聚合项目状态、负责人和交付节奏。</p>
            <div class="acceptance-project-header__meta">
              <yp-status-tag
                domain="integration"
                status="CONFIGURED"
                effect="soft"
              />
              <span class="muted-text">Light / Dark / Night × Comfortable / Compact</span>
            </div>
          </div>
        </div>
        <div class="acceptance-project-header__actions">
          <el-button @click="drawerOpen = true">
            检查浮层
          </el-button>
          <el-button type="primary">
            <el-icon aria-hidden="true">
              <folder-opened />
            </el-icon>
            创建项目
          </el-button>
        </div>
      </header>

      <el-alert
        title="验收提示"
        description="请在 1440×900、1280×800、960×768 和小于 960px 的窗口宽度检查焦点、对比度与横向表格。"
        type="info"
        show-icon
        :closable="false"
      />

      <section class="project-catalog acceptance-board-preview">
        <div class="project-list-surface">
          <div class="project-board-view-switcher">
            <div
              class="project-board-view-switcher__active"
              aria-current="page"
            >
              <el-icon aria-hidden="true">
                <grid />
              </el-icon>
              <span>项目看板</span>
            </div>
            <span class="project-board-view-switcher__description">按 Workspace 分组</span>
          </div>
          <yp-filter-bar
            :filters="[{ key: 'status', label: '状态', valueLabel: '活跃' }]"
            :result-count="rows.length"
          >
            <template #search>
              <el-input
                placeholder="搜索项目"
                clearable
              />
            </template>
            <template #filters>
              <el-select
                placeholder="全部状态"
                clearable
              >
                <el-option
                  label="活跃"
                  value="ACTIVE"
                />
                <el-option
                  label="草稿"
                  value="DRAFT"
                />
              </el-select>
            </template>
          </yp-filter-bar>
          <div class="project-board acceptance-project-board">
            <section
              v-for="group in previewGroups"
              :key="group.id"
              class="project-board-group"
              :class="`project-board-group--${group.tone}`"
            >
              <header class="project-board-group__header">
                <button
                  class="project-board-group__toggle"
                  type="button"
                  :aria-expanded="!collapsedPreviewGroups.has(group.id)"
                  :aria-controls="`acceptance-group-${group.id}`"
                  @click="togglePreviewGroup(group.id)"
                >
                  <el-icon
                    class="project-board-group__chevron"
                    :class="{ collapsed: collapsedPreviewGroups.has(group.id) }"
                    aria-hidden="true"
                  >
                    <arrow-right />
                  </el-icon>
                  <span class="project-board-group__accent" />
                  <span class="project-board-group__identity">
                    <strong>{{ group.name }}</strong>
                    <small>{{ group.code }} · {{ group.rows.length }} 个项目</small>
                  </span>
                </button>
                <div class="project-board-group__summary">
                  <span v-if="group.active">活跃 {{ group.active }}</span>
                  <span v-if="group.draft">草稿 {{ group.draft }}</span>
                  <span v-if="group.archived">已归档 {{ group.archived }}</span>
                </div>
              </header>
              <div
                v-if="!collapsedPreviewGroups.has(group.id)"
                :id="`acceptance-group-${group.id}`"
                class="project-board-group__content"
              >
                <div class="table-scroll project-desktop-table">
                  <el-table
                    class="project-board-table"
                    :data="group.rows"
                  >
                    <el-table-column
                      fixed="left"
                      label="项目"
                      min-width="300"
                    >
                      <template #default="scope">
                        <div class="project-name-cell">
                          <strong>{{ scope.row.name }}</strong>
                          <span class="project-name-cell__code">{{ scope.row.code }}</span>
                        </div>
                      </template>
                    </el-table-column>
                    <el-table-column
                      class-name="project-status-column"
                      label="状态"
                      width="132"
                    >
                      <template #default="scope">
                        <yp-status-tag
                          domain="project-lifecycle"
                          :status="scope.row.status"
                          effect="cell"
                        />
                      </template>
                    </el-table-column>
                    <el-table-column
                      label="负责人"
                      min-width="180"
                    >
                      <template #default="scope">
                        <yp-assignee
                          :user-id="`user-${scope.row.id}`"
                          :display-name="scope.row.owner"
                          size="table"
                        />
                      </template>
                    </el-table-column>
                    <el-table-column
                      prop="type"
                      label="项目类型"
                      min-width="150"
                    />
                    <el-table-column
                      prop="access"
                      label="我的角色"
                      min-width="120"
                    />
                  </el-table>
                </div>
                <ul
                  class="project-mobile-list"
                  :aria-label="`${group.name} 项目列表`"
                >
                  <li
                    v-for="row in group.rows"
                    :key="row.id"
                  >
                    <button
                      class="project-mobile-row"
                      type="button"
                    >
                      <span class="project-mobile-row__header">
                        <span class="project-mobile-row__identity">
                          <strong>{{ row.name }}</strong>
                          <span>{{ row.code }}</span>
                        </span>
                        <yp-status-tag
                          domain="project-lifecycle"
                          :status="row.status"
                          size="small"
                        />
                      </span>
                      <span class="project-mobile-row__meta">
                        <yp-assignee
                          :user-id="`user-${row.id}`"
                          :display-name="row.owner"
                          size="table"
                        />
                        <span>{{ row.type }} · {{ row.access }}</span>
                      </span>
                    </button>
                  </li>
                </ul>
              </div>
            </section>
          </div>
        </div>
      </section>

      <div class="acceptance-grid">
        <section class="surface-card page-stack">
          <div class="section-heading">
            <h2>项目表单</h2>
          </div>
          <el-form label-position="top">
            <el-form-item
              label="项目名称"
              required
            >
              <el-input v-model="project.name" />
            </el-form-item>
            <el-form-item label="项目类型">
              <el-select v-model="project.type">
                <el-option
                  label="产品研发"
                  value="PRODUCT_DEVELOPMENT"
                />
                <el-option
                  label="实施"
                  value="IMPLEMENTATION"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="说明">
              <el-input
                type="textarea"
                :rows="3"
                placeholder="输入项目说明"
              />
            </el-form-item>
            <div class="action-row">
              <el-button>取消</el-button>
              <el-button type="primary">
                保存
              </el-button>
            </div>
          </el-form>
        </section>
        <section class="surface-card page-stack">
          <div class="section-heading">
            <h2>状态</h2>
          </div>
          <div class="header-meta">
            <yp-status-tag
              domain="account"
              status="ENABLED"
            />
            <yp-status-tag
              domain="directory-sync"
              status="PARTIALLY_SUCCEEDED"
            />
            <yp-status-tag
              domain="directory-sync"
              status="FAILED"
            />
            <yp-status-tag
              domain="integration"
              status="FUTURE_STATUS"
              effect="soft"
            />
          </div>
          <yp-empty-state reason="no-results">
            <template #action>
              <el-button>清除筛选</el-button>
            </template>
          </yp-empty-state>
        </section>
      </div>

      <el-drawer
        v-model="drawerOpen"
        title="浮层与表单验收"
        size="min(560px, 100vw)"
      >
        <div class="page-stack">
          <el-alert
            title="未保存变更会在关闭前确认"
            type="warning"
            show-icon
            :closable="false"
          />
          <el-form label-position="top">
            <el-form-item label="负责人">
              <el-input v-model="project.owner" />
            </el-form-item>
          </el-form>
          <div class="action-row">
            <el-button @click="drawerOpen = false">
              关闭
            </el-button>
            <el-button type="primary">
              确认
            </el-button>
          </div>
        </div>
      </el-drawer>
      <el-button
        class="acceptance-mobile-nav"
        type="primary"
        circle
        aria-label="打开导航"
        @click="mobileNavigationOpen = true"
      >
        <el-icon aria-hidden="true">
          <menu-icon />
        </el-icon>
      </el-button>
      <el-drawer
        v-model="mobileNavigationOpen"
        title="项目空间"
        direction="ltr"
        size="min(320px, 88vw)"
      >
        <nav
          class="mobile-module-navigation"
          aria-label="窄屏模块导航"
        >
          <button
            class="active"
            type="button"
          >
            工作台
          </button>
          <button type="button">
            项目
          </button>
          <button type="button">
            身份管理
          </button>
        </nav>
        <p class="mobile-context-title">
          当前模块
        </p>
        <nav
          class="global-navigation"
          aria-label="窄屏上下文导航"
        >
          <button
            class="active"
            type="button"
          >
            项目概览
          </button>
          <button type="button">
            项目成员
          </button>
          <button type="button">
            项目设置
          </button>
        </nav>
      </el-drawer>
    </main>
  </div>
</template>

<style scoped>
.acceptance-shell {
  display: grid;
  min-height: 100vh;
  grid-template: 64px 1fr / 64px 232px minmax(0, 1fr);
  background: var(--yp-bg-canvas);
}

.acceptance-modules {
  display: flex;
  grid-row: 1 / 3;
  flex-direction: column;
  align-items: center;
  gap: var(--yp-space-3);
  padding: var(--yp-space-3) var(--yp-space-2);
  color: var(--yp-text-on-brand);
  background: var(--yp-bg-module-rail);
}

.acceptance-modules strong,
.acceptance-modules button {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border: 0;
  border-radius: var(--yp-radius-md);
  color: inherit;
  background: transparent;
}

.acceptance-modules strong,
.acceptance-modules .active { background: var(--yp-bg-module-active); }
.acceptance-topbar { display: flex; grid-column: 2 / 4; align-items: center; justify-content: space-between; padding: 0 var(--yp-space-6); border-bottom: 1px solid var(--yp-border-default); background: var(--yp-bg-surface); }
.acceptance-topbar__context { display: flex; align-items: center; gap: var(--yp-space-3); min-width: 0; }
.acceptance-context-toggle { display: none; }
.acceptance-context { display: flex; flex-direction: column; gap: var(--yp-space-2); padding: var(--yp-space-6) var(--yp-space-4); border-right: 1px solid var(--yp-border-default); background: var(--yp-bg-subtle); }
.acceptance-context h2 { margin: 0 0 var(--yp-space-4); }
.acceptance-context button { padding: var(--yp-space-2) var(--yp-space-3); border: 0; border-radius: var(--yp-radius-md); color: var(--yp-text-secondary); background: transparent; text-align: left; }
.acceptance-context button.active { color: var(--yp-link); background: var(--yp-bg-selected); font-weight: 600; }
.acceptance-content { min-width: 0; padding: var(--yp-space-8); }
.acceptance-content > * + * { margin-top: var(--yp-space-6); }
.acceptance-project-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--yp-space-6); padding: var(--yp-space-2) 0 var(--yp-space-4); }
.acceptance-project-header__identity { display: flex; min-width: 0; align-items: flex-start; gap: var(--yp-space-4); }
.acceptance-project-header__icon { display: grid; width: 56px; height: 56px; flex: 0 0 56px; place-items: center; border-radius: var(--yp-radius-lg); color: var(--yp-link); background: var(--yp-bg-selected); font-size: 28px; box-shadow: none; }
.acceptance-project-header h1 { margin: 0; color: var(--yp-text-primary); font-size: 32px; line-height: 1.2; letter-spacing: -0.02em; }
.acceptance-project-header p { margin: var(--yp-space-2) 0 0; color: var(--yp-text-secondary); }
.acceptance-project-header__meta { display: flex; flex-wrap: wrap; align-items: center; gap: var(--yp-space-3); margin-top: var(--yp-space-3); }
.acceptance-project-header__actions { display: flex; flex: 0 0 auto; gap: var(--yp-space-2); }
.acceptance-board-preview { width: 100%; }
.acceptance-grid { display: grid; gap: var(--yp-space-6); grid-template-columns: repeat(2, minmax(0, 1fr)); }
.acceptance-mobile-nav { display: none; }

@media (min-width: 960px) and (max-width: 1279px) {
  .acceptance-shell { grid-template: 64px 1fr / 64px minmax(0, 1fr); }
  .acceptance-topbar { grid-column: 2; }
  .acceptance-context-toggle { display: inline-flex; }
  .acceptance-context { position: absolute; z-index: 20; top: 64px; bottom: 0; left: 64px; width: 232px; background: var(--yp-bg-surface); box-shadow: var(--yp-shadow-popover); transform: translateX(-110%); transition: transform var(--yp-motion-popover) var(--yp-ease-standard); }
  .acceptance-shell--context-open .acceptance-context { transform: translateX(0); }
  .acceptance-content { grid-column: 2; }
}

@media (max-width: 959px) {
  .acceptance-shell { grid-template: 64px 1fr / minmax(0, 1fr); }
  .acceptance-modules,
  .acceptance-context { display: none; }
  .acceptance-topbar { grid-column: 1; }
  .acceptance-content { grid-column: 1; padding: var(--yp-space-4); }
  .acceptance-project-header { flex-direction: column; }
  .acceptance-project-header__actions { width: 100%; }
  .acceptance-grid { grid-template-columns: 1fr; }
  .acceptance-mobile-nav { position: fixed; z-index: 3; right: var(--yp-space-4); bottom: var(--yp-space-4); display: inline-flex; }
}

@media (max-width: 640px) {
  .acceptance-topbar { min-height: 64px; padding: var(--yp-space-3); }
  .acceptance-content { padding: var(--yp-space-3); }
  .acceptance-project-header__icon { width: 48px; height: 48px; flex-basis: 48px; font-size: 24px; }
  .acceptance-project-header h1 { font-size: 26px; }
  .acceptance-project-header__actions .el-button { flex: 1; }
}
</style>
