import type { ErrorResponse } from '../src/generated/models/ErrorResponse.js'

const emptyDetails: ErrorResponse['details'] = {}
const reasonDetails: ErrorResponse['details'] = { reason: 'OWNER_MISSING' }
const rejectedDetails: ErrorResponse['details'] = {
  // @ts-expect-error -- details 只允许稳定的 reason 字段。
  secret: true,
}

void emptyDetails
void reasonDetails
void rejectedDetails
