import type { ArchiveProjectRequest, UpdateProjectRequest } from '../src/generated/apis/ProjectsApi.js'

const update: UpdateProjectRequest = {
  projectId: '26000000-0000-4000-8000-000000000001', xXSRFTOKEN: 'csrf-token', ifMatch: '"3"',
  projectUpdateRequest: { name: '交付项目', description: null },
}
// @ts-expect-error PATCH 请求体禁止携带并发版本字段。
update.projectUpdateRequest.rowVersion = 3
// @ts-expect-error 归档必须携带 If-Match。
const missingIfMatch: ArchiveProjectRequest = { projectId: update.projectId, xXSRFTOKEN: 'csrf', idempotencyKey: '26000000-0000-4000-8000-000000000002' }
// @ts-expect-error 归档必须携带 Idempotency-Key。
const missingKey: ArchiveProjectRequest = { projectId: update.projectId, xXSRFTOKEN: 'csrf', ifMatch: '"3"' }
void update
void missingIfMatch
void missingKey
