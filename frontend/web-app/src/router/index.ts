import { AuthenticationRole } from '@yumpoo/api-client'
import { watch } from 'vue'
import {
  createRouter,
  createWebHistory,
  type RouteLocationNormalized,
  type RouteRecordRaw,
} from 'vue-router'
import { consumeReturnPath, rememberReturnPath } from '../auth/navigation'
import AppShell from '../components/AppShell.vue'
import { useSession } from '../composables/useSession'
import LoginView from '../views/auth/LoginView.vue'
import HomeView from '../views/home/HomeView.vue'
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
import ProjectProductsView from '../views/projects/ProjectProductsView.vue'
import ProjectContentsView from '../views/projects/ProjectContentsView.vue'
import ContentWorkItemsView from '../views/projects/ContentWorkItemsView.vue'
import ProjectSettingsView from '../views/projects/ProjectSettingsView.vue'

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
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
        path: '',
        name: 'home',
        component: HomeView,
        meta: { shellSection: 'work' },
      },
      {
        path: 'projects',
        name: 'projects',
        component: ProjectsView,
        meta: { shellSection: 'projects' },
      },
      {
        path: 'projects/:projectId',
        component: ProjectLayout,
        redirect: route => ({ name: 'project-overview', params: route.params }),
        meta: { shellSection: 'projects' },
        children: [
          { path: 'overview', name: 'project-overview', component: ProjectOverviewView },
          { path: 'contents', name: 'project-contents', component: ProjectContentsView },
          { path: 'contents/:contentId', name: 'content-work-items', component: ContentWorkItemsView },
          { path: 'members', name: 'project-members', component: ProjectMembersView },
          { path: 'products', name: 'project-products', component: ProjectProductsView },
          { path: 'settings', name: 'project-settings', component: ProjectSettingsView },
        ],
      },
      {
        path: 'admin/identity',
        component: IdentityAdminLayout,
        redirect: '/admin/identity/overview',
        meta: {
          shellSection: 'identity',
          requiredRoles: [
            AuthenticationRole.AppManager,
            AuthenticationRole.CompanyAdmin,
          ],
        },
        children: [
          {
            path: 'overview',
            name: 'identity-overview',
            component: IdentityOverviewView,
          },
          {
            path: 'sync-runs',
            name: 'identity-sync-runs',
            component: IdentitySyncRunsView,
          },
          {
            path: 'members',
            name: 'identity-members',
            component: IdentityMembersView,
          },
        ],
      },
      {
        path: 'forbidden',
        name: 'forbidden',
        component: ForbiddenView,
        meta: { shellSection: 'work' },
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
  const session = useSession()
  await session.ensureAuthentication()
  return sessionDestination(to)
})

watch(useSession().phase, (next) => {
  const current = router.currentRoute.value
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

function sessionDestination(to: RouteLocationNormalized) {
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
  if (to.name === 'home') {
    const returnPath = consumeReturnPath()
    if (returnPath !== '/') {
      return returnPath
    }
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
