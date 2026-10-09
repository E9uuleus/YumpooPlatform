import {
  OperationsApi,
  NotificationsApi,
  DashboardsApi,
  TeamDashboardApi,
  TimeTrackingApi,
  AuthenticationApi,
  AdministrationApi,
  IdentityAdministrationApi,
  IdentityGovernanceApi,
  ProjectsApi,
  WorkspacesApi,
  ContentsApi,
  WorkItemsApi,
  WorkItemUpdatesApi,
  AttachmentsApi,
  ActivityApi,
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
export const workspacesApi = new WorkspacesApi(yumpooApiClient)
export const contentsApi = new ContentsApi(yumpooApiClient)
export const workItemsApi = new WorkItemsApi(yumpooApiClient)
export const workItemUpdatesApi = new WorkItemUpdatesApi(yumpooApiClient)
export const attachmentsApi = new AttachmentsApi(yumpooApiClient)
export const activityApi = new ActivityApi(yumpooApiClient)

export const timeTrackingApi = new TimeTrackingApi(yumpooApiClient)
export const dashboardsApi = new DashboardsApi(yumpooApiClient)
export const teamDashboardApi = new TeamDashboardApi(yumpooApiClient)
export const notificationsApi = new NotificationsApi(yumpooApiClient)
export const operationsApi = new OperationsApi(yumpooApiClient)
