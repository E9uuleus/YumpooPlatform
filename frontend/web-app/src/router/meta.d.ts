import type { AuthenticationRole } from '@yumpoo/api-client'
import type { SessionPhase } from '../composables/useSession'
import type { ShellSection } from './shell-navigation'

declare module 'vue-router' {
  interface RouteMeta {
    requiredRoles?: AuthenticationRole[]
    shellSection?: ShellSection
    sessionStatus?: Extract<
      SessionPhase,
      'accountDisabled' | 'upgradeRequired' | 'failure'
    >
  }
}

export {}
