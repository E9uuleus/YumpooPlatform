import type { AuthenticationRole } from '@yumpoo/api-client'
import type { SessionPhase } from '../composables/useSession'
import type { TourId } from '../onboarding/tours'
import type { ShellSection } from './shell-navigation'

declare module 'vue-router' {
  interface RouteMeta {
    onboardingTour?: TourId
    /** Reachable without a session; the guard and session redirects leave it alone. */
    public?: boolean
    requiredRoles?: AuthenticationRole[]
    shellSection?: ShellSection
    sessionStatus?: Extract<
      SessionPhase,
      'accountDisabled' | 'upgradeRequired' | 'failure'
    >
  }
}

export {}
