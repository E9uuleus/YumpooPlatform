import {
  AuthenticationApi,
  IdentityAdministrationApi,
  IdentityGovernanceApi,
  createYumpooApiClient,
} from '@yumpoo/api-client'

export const yumpooApiClient = createYumpooApiClient()
export const authenticationApi = new AuthenticationApi(yumpooApiClient)
export const identityAdministrationApi = new IdentityAdministrationApi(yumpooApiClient)
export const identityGovernanceApi = new IdentityGovernanceApi(yumpooApiClient)
