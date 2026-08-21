import {
  AuthenticationRole,
  ClientCompatibility,
  ErrorCode,
  readCsrfToken,
  type CurrentAuthentication,
} from '@yumpoo/api-client'
import { computed, ref } from 'vue'
import { authenticationApi } from '../api/client'
import {
  isProblemCode,
  localProblem,
  subscribeGlobalProblems,
  toApiProblem,
  type ApiProblem,
} from '../api/problems'

export type SessionPhase =
  | 'checking'
  | 'authenticated'
  | 'anonymous'
  | 'accountDisabled'
  | 'upgradeRequired'
  | 'failure'

const phase = ref<SessionPhase>('checking')
const authentication = ref<CurrentAuthentication>()
const blockingProblem = ref<ApiProblem>()
const actionProblem = ref<ApiProblem>()
const logoutLoading = ref(false)
let pendingAuthentication: Promise<void> | undefined

subscribeGlobalProblems(problem => {
  applyGlobalProblem(problem)
})

export function useSession() {
  const isIdentityReader = computed(() => authentication.value
    ? authentication.value.roles.has(AuthenticationRole.AppManager)
      || authentication.value.roles.has(AuthenticationRole.CompanyAdmin)
    : false)
  const canManageIdentity = computed(() => authentication.value?.roles.has(
    AuthenticationRole.CompanyAdmin,
  ) ?? false)
  const isCompanyAdmin = computed(() => authentication.value?.roles.has(
    AuthenticationRole.CompanyAdmin,
  ) ?? false)

  return {
    phase,
    authentication,
    blockingProblem,
    actionProblem,
    logoutLoading,
    isIdentityReader,
    canManageIdentity,
    isCompanyAdmin,
    ensureAuthentication,
    logout,
    clearActionProblem,
  }
}

export async function ensureAuthentication(force = false): Promise<void> {
  if (!force && phase.value === 'authenticated' && authentication.value) {
    return
  }
  if (pendingAuthentication) {
    return pendingAuthentication
  }
  phase.value = 'checking'
  blockingProblem.value = undefined
  pendingAuthentication = authenticationApi.getCurrentAuthentication()
    .then((result) => {
      authentication.value = result
      if (result.client.compatibility === ClientCompatibility.Blocked) {
        phase.value = 'upgradeRequired'
      } else if (result.client.compatibility === ClientCompatibility.Supported
        || result.client.compatibility === ClientCompatibility.Deprecated) {
        phase.value = 'authenticated'
      } else {
        authentication.value = undefined
        phase.value = 'failure'
        blockingProblem.value = localProblem('客户端兼容状态无法识别，请联系管理员。')
      }
    })
    .catch(async (reason: unknown) => {
      const problem = await toApiProblem(reason)
      if (!applyGlobalProblem(problem)) {
        authentication.value = undefined
        blockingProblem.value = problem
        phase.value = 'failure'
      }
    })
    .finally(() => {
      pendingAuthentication = undefined
    })
  return pendingAuthentication
}

export async function logout(): Promise<boolean> {
  clearActionProblem()
  const csrf = readCsrfToken()
  if (!csrf) {
    actionProblem.value = localProblem('缺少 CSRF 凭据，请刷新页面后重试。')
    return false
  }
  logoutLoading.value = true
  try {
    await authenticationApi.logoutCurrentSession({ xXSRFTOKEN: csrf })
    await window.yumpooDesktop?.auth.clear().catch(() => undefined)
    setAnonymous()
    return true
  } catch (reason) {
    const problem = await toApiProblem(reason)
    if (isProblemCode(problem, ErrorCode.AuthenticationRequired)) {
      setAnonymous()
      return true
    }
    if (!applyGlobalProblem(problem)) {
      actionProblem.value = problem
    }
    return false
  } finally {
    logoutLoading.value = false
  }
}

export function clearActionProblem(): void {
  actionProblem.value = undefined
}

function applyGlobalProblem(problem: ApiProblem): boolean {
  if (isProblemCode(problem, ErrorCode.AuthenticationRequired)) {
    setAnonymous()
    return true
  }
  if (isProblemCode(problem, ErrorCode.AccountDisabled)) {
    authentication.value = undefined
    blockingProblem.value = problem
    phase.value = 'accountDisabled'
    return true
  }
  if (isProblemCode(problem, ErrorCode.ClientUpgradeRequired)) {
    blockingProblem.value = problem
    phase.value = 'upgradeRequired'
    return true
  }
  return false
}

function setAnonymous(): void {
  void window.yumpooDesktop?.auth.clear().catch(() => undefined)
  authentication.value = undefined
  blockingProblem.value = undefined
  phase.value = 'anonymous'
}
