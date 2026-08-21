import {
  AuthenticationApi,
  IdentityAdministrationApi,
  IdentityGovernanceApi,
  ProjectsApi,
  WorkspacesApi,
  ProjectTemplatesApi,
  createYumpooApiClient,
} from '@yumpoo/api-client'
import { globalProblemMiddleware } from './problems'

export const yumpooApiClient = createYumpooApiClient({
  middleware: [globalProblemMiddleware],
})
export const authenticationApi = new AuthenticationApi(yumpooApiClient)
export const identityAdministrationApi = new IdentityAdministrationApi(yumpooApiClient)
export const identityGovernanceApi = new IdentityGovernanceApi(yumpooApiClient)
export const projectsApi = new ProjectsApi(yumpooApiClient)
export const workspacesApi = new WorkspacesApi(yumpooApiClient)
export const projectTemplatesApi = new ProjectTemplatesApi(yumpooApiClient)
