import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import IdentityAdminLayout from '../views/admin/IdentityAdminLayout.vue'
import IdentityOverviewView from '../views/admin/IdentityOverviewView.vue'
import IdentitySyncRunsView from '../views/admin/IdentitySyncRunsView.vue'
import IdentityMembersView from '../views/admin/IdentityMembersView.vue'

export const routes = [
  {
    path: '/',
    name: 'home',
    component: HomeView,
  },
  {
    path: '/admin/identity',
    component: IdentityAdminLayout,
    redirect: '/admin/identity/overview',
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
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

export default createRouter({
  history: createWebHistory(),
  routes,
})
