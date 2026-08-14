import type {
  GovernanceReasonRequest,
  RoleGrantRequest,
} from '../src/generated/models/index.js'
import type {
  GrantAppManagerRequest,
  RevokeCompanyAdminRequest,
} from '../src/generated/apis/IdentityGovernanceApi.js'

const grantBody: RoleGrantRequest = {
  userId: '00000000-0000-4000-8000-000000000001',
  reason: 'security review',
}

const reasonBody: GovernanceReasonRequest = { reason: 'rotation complete' }

const grant: GrantAppManagerRequest = {
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '10000000-0000-4000-8000-000000000010',
  ifMatch: '"3"',
  roleGrantRequest: grantBody,
}

const revoke: RevokeCompanyAdminRequest = {
  assignmentId: '20000000-0000-4000-8000-000000000010',
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '30000000-0000-4000-8000-000000000010',
  ifMatch: '"4"',
  governanceReasonRequest: reasonBody,
}

const forbiddenVersion: RoleGrantRequest = {
  userId: grantBody.userId,
  reason: grantBody.reason,
  // @ts-expect-error -- 版本只允许通过 If-Match 传递。
  rowVersion: 3,
}

const forbiddenActor: GovernanceReasonRequest = {
  reason: reasonBody.reason,
  // @ts-expect-error -- actor 只能来自服务端 Session。
  actorUserId: '40000000-0000-4000-8000-000000000010',
}

void grant
void revoke
void forbiddenVersion
void forbiddenActor
