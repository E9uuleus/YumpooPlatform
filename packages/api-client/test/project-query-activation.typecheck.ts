import type {
  ActivateProjectRequest,
  UpdateProjectRequest,
} from '../src/generated/apis/ProjectsApi.js'

const update: UpdateProjectRequest = {
  projectId: '26000000-0000-4000-8000-000000000001',
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"3"',
  projectUpdateRequest: {
    name: '交付项目',
    description: null,
    customerName: 'Yumpoo',
    customerReference: null,
    deliverySite: null,
    contactNote: null,
  },
}

const activate: ActivateProjectRequest = {
  projectId: update.projectId,
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"3"',
  idempotencyKey: '26000000-0000-4000-8000-000000000002',
}

// @ts-expect-error PATCH 请求体禁止携带并发版本字段。
update.projectUpdateRequest.rowVersion = 3

// @ts-expect-error 激活必须携带 If-Match。
const missingIfMatch: ActivateProjectRequest = {
  projectId: update.projectId,
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: activate.idempotencyKey,
}

// @ts-expect-error 激活必须携带 Idempotency-Key。
const missingIdempotencyKey: ActivateProjectRequest = {
  projectId: update.projectId,
  xXSRFTOKEN: 'csrf-token',
  ifMatch: activate.ifMatch,
}

void activate
void missingIfMatch
void missingIdempotencyKey
