import type { AuthenticationRole } from '@yumpoo/api-client'
import type { SessionPhase } from '../composables/useSession'

declare module 'vue-router' {
  interface RouteMeta {
    requiredRoles?: AuthenticationRole[]
    sessionStatus?: Extract<
      SessionPhase,
      'accountDisabled' | 'upgradeRequired' | 'failure'
    >
  }
}

export {}
