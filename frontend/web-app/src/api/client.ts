import {
  AuthenticationApi,
  IdentityAdministrationApi,
  IdentityGovernanceApi,
  createYumpooApiClient,
} from '@yumpoo/api-client'
import { globalProblemMiddleware } from './problems'

export const yumpooApiClient = createYumpooApiClient({
  middleware: [globalProblemMiddleware],
})
export const authenticationApi = new AuthenticationApi(yumpooApiClient)
export const identityAdministrationApi = new IdentityAdministrationApi(yumpooApiClient)
export const identityGovernanceApi = new IdentityGovernanceApi(yumpooApiClient)
