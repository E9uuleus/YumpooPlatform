import {
  AccountStatus,
  DirectorySyncRunStatus,
  EmploymentStatus,
  IdentityAdministrationApi,
  createYumpooApiClient,
  readCsrfToken,
  type DirectorySyncRun,
  type MemberPage,
  type WeComIntegrationStatus,
} from '../src/index.js'

const api = new IdentityAdministrationApi(createYumpooApiClient())

const companyPromise = api.getCompany()
const statusPromise: Promise<WeComIntegrationStatus> = api.getWeComIntegrationStatus()
const membersPromise: Promise<MemberPage> = api.listMembers({
  employmentStatus: EmploymentStatus.Active,
  accountStatus: AccountStatus.Enabled,
  page: 0,
  size: 20,
})
const runsPromise = api.listDirectorySyncRuns({
  status: DirectorySyncRunStatus.Running,
})

const csrf = readCsrfToken()
if (csrf) {
  const triggerPromise: Promise<DirectorySyncRun> = api.triggerDirectorySync({
    xXSRFTOKEN: csrf,
    idempotencyKey: crypto.randomUUID(),
  })
  void triggerPromise
}

void companyPromise
void statusPromise
void membersPromise
void runsPromise
