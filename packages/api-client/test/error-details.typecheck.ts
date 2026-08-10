import type { ErrorResponse } from '../src/generated/models/ErrorResponse.js'

const emptyDetails: ErrorResponse['details'] = {}
const rejectedDetails: ErrorResponse['details'] = {
  // @ts-expect-error -- EmptyErrorDetails 不允许任意键值。
  secret: true,
}

void emptyDetails
void rejectedDetails
