import {
  AuthenticationRole,
  ErrorResponseFromJSON,
  ResponseError,
  type CurrentAuthentication,
  type ErrorResponse,
} from '@yumpoo/api-client'
import { computed, ref } from 'vue'
import { authenticationApi } from '../api/client'

const authentication = ref<CurrentAuthentication>()
const authenticationError = ref<UiError>()
const authenticationLoading = ref(false)
let pendingAuthentication: Promise<void> | undefined

export interface UiError {
  message: string
  requestId?: string
  code?: string
  status?: number
  location?: string
}

export function useIdentityAdmin() {
  const isReader = computed(() => authentication.value
    ? authentication.value.roles.has(AuthenticationRole.AppManager)
      || authentication.value.roles.has(AuthenticationRole.CompanyAdmin)
    : false)
  const canWrite = computed(() => authentication.value?.roles.has(
    AuthenticationRole.CompanyAdmin,
  ) ?? false)

  async function loadAuthentication(): Promise<void> {
    if (authentication.value || pendingAuthentication) {
      return pendingAuthentication
    }
    authenticationLoading.value = true
    authenticationError.value = undefined
    pendingAuthentication = authenticationApi.getCurrentAuthentication()
      .then((result) => {
        authentication.value = result
      })
      .catch(async (error: unknown) => {
        authenticationError.value = await toUiError(error)
      })
      .finally(() => {
        authenticationLoading.value = false
        pendingAuthentication = undefined
      })
    return pendingAuthentication
  }

  return {
    authentication,
    authenticationError,
    authenticationLoading,
    isReader,
    canWrite,
    loadAuthentication,
  }
}

export async function toUiError(error: unknown): Promise<UiError> {
  if (error instanceof ResponseError) {
    let body: ErrorResponse | undefined
    try {
      body = ErrorResponseFromJSON(await error.response.clone().json())
    } catch {
      body = undefined
    }
    return {
      message: body?.message ?? '请求未能完成，请稍后重试。',
      ...(body?.requestId ? { requestId: body.requestId } : {}),
      ...(body?.code ? { code: body.code } : {}),
      status: error.response.status,
      ...(error.response.headers.get('Location')
        ? { location: error.response.headers.get('Location')! }
        : {}),
    }
  }
  return { message: '网络连接异常，请检查连接后重试。' }
}
