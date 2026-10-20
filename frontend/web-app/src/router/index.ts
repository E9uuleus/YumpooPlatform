import { AuthenticationRole } from '@yumpoo/api-client'
import { watch } from 'vue'
import {
  createRouter,
  createWebHistory,
  START_LOCATION,
  type RouteLocationNormalized,
  type RouteRecordRaw,
} from 'vue-router'
import { consumeReturnPath, rememberReturnPath } from '../auth/navigation'
import TimerPanel from '../components/timer/TimerPanel.vue'
import AppShell from '../components/AppShell.vue'
import { useSession } from '../composables/useSession'
import LoginView from '../views/auth/LoginView.vue'
import ForbiddenView from '../views/status/ForbiddenView.vue'
import NotFoundView from '../views/status/NotFoundView.vue'
import SessionStatusView from '../views/status/SessionStatusView.vue'
import IdentityAdminLayout from '../views/admin/IdentityAdminLayout.vue'
import IdentityMembersView from '../views/admin/IdentityMembersView.vue'
import IdentityOverviewView from '../views/admin/IdentityOverviewView.vue'
import IdentitySyncRunsView from '../views/admin/IdentitySyncRunsView.vue'
import ProjectsView from '../views/projects/ProjectsView.vue'
import ProjectLayout from '../views/projects/ProjectLayout.vue'
import ProjectOverviewView from '../views/projects/ProjectOverviewView.vue'
import ProjectMembersView from '../views/projects/ProjectMembersView.vue'
import ProjectSettingsView from '../views/projects/ProjectSettingsView.vue'
import ProjectActivityView from '../views/projects/ProjectActivityView.vue'

export const routes: RouteRecordRaw[] = [
  { path: '/timer', name: 'timer', component: TimerPanel, props: route => ({ initialExpanded: route.query.mode !== 'compact' }) },
  // Desktop shell windows outside AppShell, like /timer; only shells from 1.1.0 load them.
  { path: '/desktop/quick', name: 'desktop-quick', component: () => import('../views/desktop/QuickTimeView.vue') },
  { path: '/desktop/rail', name: 'desktop-rail', component: () => import('../views/desktop/ProjectRailView.vue') },
  {
    path: '/login',
    name: 'login',
    component: LoginView,
  },
  {
    path: '/download',
    name: 'desktop-download',
    component: () => import('../views/download/DesktopDownloadView.vue'),
    meta: { public: true },
  },
  {
    path: '/status/account-disabled',
    name: 'account-disabled',
    component: SessionStatusView,
    meta: { sessionStatus: 'accountDisabled' },
  },
  {
    path: '/status/upgrade-required',
    name: 'upgrade-required',
    component: SessionStatusView,
    meta: { sessionStatus: 'upgradeRequired' },
  },
  {
    path: '/status/unavailable',
    name: 'unavailable',
    component: SessionStatusView,
    meta: { sessionStatus: 'failure' },
  },
  {
    path: '/',
    component: AppShell,
    children: [
      {
        path: 'admin/operations',
        alias: '/operations',
        component: () => import('../views/operations/OperationsLayout.vue'),
        redirect: { name: 'operations-overview' },
        meta: { shellSection: 'operations', requiredRoles: [AuthenticationRole.AppManager] },
        children: [
          { path: 'overview', name: 'operations-overview', component: () => import('../views/operations/OperationsOverviewView.vue') },
          { path: 'host', name: 'operations-host', component: () => import('../views/operations/OperationsHostView.vue') },
          { path: 'sessions', name: 'operations-sessions', component: () => import('../views/operations/OperationsSessionsView.vue') },
          { path: 'logs', name: 'operations-logs', component: () => import('../views/operations/OperationsLogsView.vue') },
          { path: 'alerts', name: 'operations-alerts', component: () => import('../views/operations/OperationsAlertsView.vue') },
        ],
      },
      {
        path: '',
        name: 'home',
        component: () => import('../views/home/HomeView.vue'),
        meta: { shellSection: 'home', onboardingTour: 'welcome' },
      },
      {
        path: 'workspace',
        name: 'workspace-entry',
        component: ProjectsView,
        meta: { shellSection: 'work' },
      },
      {
        path: 'workspace/:workspaceSlug',
        name: 'workspace',
        component: ProjectsView,
        meta: { shellSection: 'work', onboardingTour: 'workspace' },
      },
      {
        path: 'dashboards/team',
        name: 'team-dashboard-entry',
        component: () => import('../views/DashboardsView.vue'),
        meta: { shellSection: 'work' },
      },
      {
        path: 'workspace/:workspaceSlug/dashboards/:dashboardId?',
        name: 'dashboards',
        component: () => import('../views/DashboardsView.vue'),
        meta: { shellSection: 'work', onboardingTour: 'dashboards' },
      },
      {
        path: 'workspace/:workspaceSlug/my-time',
        name: 'my-time',
        component: () => import('../views/MyTimePageView.vue'),
        meta: { shellSection: 'work' },
      },
      {
        path: 'workspace/:workspaceSlug/my-time/day/:date(\\d{4}-\\d{2}-\\d{2})',
        name: 'my-time-day',
        component: () => import('../views/MyTimeDayPageView.vue'),
        meta: { shellSection: 'work' },
      },
      { path: 'team', name: 'team-entry', redirect: { name: 'team', params: { view: 'timesheet' } } },
      {
        path: 'team/:view(timesheet|overtime|workload)',
        name: 'team',
        component: () => import('../views/TeamReviewView.vue'),
        meta: { shellSection: 'team', requiredRoles: [AuthenticationRole.AppManager, AuthenticationRole.CompanyAdmin] },
      },
      {
        path: 'projects/archived',
        name: 'archived-projects',
        component: () => import('../views/projects/ArchivedProjectsView.vue'),
        meta: { shellSection: 'work' },
      },
      {
        path: 'projects/:projectId',
        component: ProjectLayout,
        redirect: route => ({ name: 'project-overview', params: route.params }),
        meta: { shellSection: 'work' },
        children: [
          { path: 'overview', name: 'project-overview', component: ProjectOverviewView, meta: { onboardingTour: 'project-work-items' } },
          { path: 'statistics', name: 'project-statistics', component: () => import('../views/projects/ProjectStatisticsView.vue') },
          { path: 'members', name: 'project-members', component: ProjectMembersView },
          { path: 'activity', name: 'project-activity', component: ProjectActivityView },
          { path: 'settings', name: 'project-settings', component: ProjectSettingsView },
        ],
      },
      {
        path: 'admin/company',
        component: IdentityAdminLayout,
        redirect: '/admin/company/overview',
        meta: {
          shellSection: 'company',
          requiredRoles: [
            AuthenticationRole.AppManager,
            AuthenticationRole.CompanyAdmin,
          ],
        },
        children: [
          { path: 'overview', name: 'company-overview', component: IdentityOverviewView, meta: { onboardingTour: 'company' } },
          { path: 'projects', name: 'company-projects', component: () => import('../views/admin/CompanyProjectsView.vue') },
          { path: 'members', name: 'company-members', component: IdentityMembersView },
          { path: 'activity', name: 'company-activity', component: () => import('../views/admin/CompanyActivityView.vue') },
          { path: 'sync-runs', name: 'company-sync-runs', component: IdentitySyncRunsView },
          { path: 'scheduled-jobs', name: 'company-scheduled-jobs', component: () => import('../views/admin/CompanyScheduledJobsView.vue') },
          { path: 'work-calendar', name: 'company-work-calendar', component: () => import('../views/admin/CompanyWorkCalendarView.vue') },
        ],
      },
      {
        path: 'admin/identity/:section(overview|members|sync-runs)?',
        redirect: route => ({ path: `/admin/company/${route.params.section || 'overview'}`, query: route.query, hash: route.hash }),
      },
      {
        path: 'forbidden',
        name: 'forbidden',
        component: ForbiddenView,
        meta: { shellSection: 'work' },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('../views/settings/SettingsView.vue'),
        meta: { shellSection: 'work' },
      },
      {
        path: 'inbox',
        name: 'inbox',
        component: () => import('../views/inbox/InboxView.vue'),
        meta: { shellSection: 'inbox', onboardingTour: 'inbox' },
      },
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: NotFoundView,
        meta: { shellSection: 'work' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  // First installs and upgrade-required clients reach the download page without signing in.
  if (to.meta.public) return true
  const session = useSession()
  await session.ensureAuthentication()
  return sessionDestination(to)
})

watch(useSession().phase, (next) => {
  const current = router.currentRoute.value
  // Until the first navigation lands, its guard owns the redirect. Replacing here would cancel it, and the next
  // guard's failed session check would replace again: an endless /auth/me loop with nothing ever rendered.
  if (current === START_LOCATION || current.meta.public) return
  if (next === 'anonymous') {
    if (current.name !== 'login') {
      rememberReturnPath(current.fullPath)
      void router.replace({ name: 'login' })
    }
  } else if (next === 'accountDisabled' && current.name !== 'account-disabled') {
    void router.replace({ name: 'account-disabled' })
  } else if (next === 'upgradeRequired' && current.name !== 'upgrade-required') {
    void router.replace({ name: 'upgrade-required' })
  } else if (next === 'failure' && current.name !== 'unavailable') {
    void router.replace({ name: 'unavailable' })
  } else if (next === 'authenticated' && (current.meta.sessionStatus || current.name === 'login')) {
    void router.replace(consumeReturnPath())
  }
})

export function sessionDestination(to: RouteLocationNormalized) {
  const session = useSession()
  if (session.phase.value === 'anonymous') {
    if (to.name === 'login') return true
    rememberReturnPath(to.fullPath)
    return { name: 'login' }
  }
  const statusRoute = routeForPhase(session.phase.value)
  if (statusRoute) {
    return to.name === statusRoute ? true : { name: statusRoute }
  }
  if (session.phase.value !== 'authenticated' || !session.authentication.value) {
    return to.name === 'unavailable' ? true : { name: 'unavailable' }
  }
  if (to.meta.sessionStatus) {
    return consumeReturnPath()
  }
  if (to.name === 'login') {
    return consumeReturnPath()
  }
  const requiredRoles = to.meta.requiredRoles
  if (requiredRoles?.length
    && !requiredRoles.some(role => session.authentication.value?.roles.has(role))) {
    return to.name === 'forbidden' ? true : { name: 'forbidden' }
  }
  const workspaceSlug = session.authentication.value.user.workspaceSlug
  if (to.name === 'workspace-entry') {
    return { name: 'workspace', params: { workspaceSlug }, replace: true }
  }
  if (to.name === 'team-dashboard-entry' || (to.name === 'dashboards' && to.params.dashboardId === 'team')) {
    return { name: 'team', params: { view: 'timesheet' }, query: to.query, hash: to.hash, replace: true }
  }
  if (to.name === 'dashboards' && to.params.dashboardId === 'my-time') {
    return { name: 'my-time', params: { workspaceSlug }, replace: true }
  }
  if (to.name === 'my-time' && typeof to.query.day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(to.query.day)) {
    return { name: 'my-time-day', params: { workspaceSlug, date: to.query.day }, replace: true }
  }
  if ((to.name === 'workspace' || to.name === 'dashboards' || to.name === 'my-time' || to.name === 'my-time-day')
    && to.params.workspaceSlug !== workspaceSlug) {
    return { name: to.name, params: { ...to.params, workspaceSlug }, replace: true }
  }
  return true
}

function routeForPhase(phase: import('../composables/useSession').SessionPhase) {
  if (phase === 'accountDisabled') return 'account-disabled'
  if (phase === 'upgradeRequired') return 'upgrade-required'
  if (phase === 'failure') return 'unavailable'
  return undefined
}

export default router
