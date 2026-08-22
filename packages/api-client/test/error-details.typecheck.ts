import type { ErrorResponse } from '../src/generated/models/ErrorResponse.js'

const emptyDetails: ErrorResponse['details'] = {}
const reasonDetails: ErrorResponse['details'] = { reason: 'OWNER_MISSING' }
const rejectedDetails: ErrorResponse['details'] = {
  blockers: [],
  // @ts-expect-error -- details 只允许稳定 reason 与安全 blocker 计数。
  secret: true,
}

void emptyDetails
void reasonDetails
void rejectedDetails
