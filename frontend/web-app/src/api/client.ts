import {
  AuthenticationApi,
  AdministrationApi,
  IdentityAdministrationApi,
  IdentityGovernanceApi,
  ProjectsApi,
  ProductsApi,
  WorkspacesApi,
  ProjectTemplatesApi,
  createYumpooApiClient,
} from '@yumpoo/api-client'
import { globalProblemMiddleware } from './problems'

export const yumpooApiClient = createYumpooApiClient({
  middleware: [globalProblemMiddleware],
})
export const authenticationApi = new AuthenticationApi(yumpooApiClient)
export const administrationApi = new AdministrationApi(yumpooApiClient)
export const identityAdministrationApi = new IdentityAdministrationApi(yumpooApiClient)
export const identityGovernanceApi = new IdentityGovernanceApi(yumpooApiClient)
export const projectsApi = new ProjectsApi(yumpooApiClient)
export const productsApi = new ProductsApi(yumpooApiClient)
export const workspacesApi = new WorkspacesApi(yumpooApiClient)
export const projectTemplatesApi = new ProjectTemplatesApi(yumpooApiClient)
