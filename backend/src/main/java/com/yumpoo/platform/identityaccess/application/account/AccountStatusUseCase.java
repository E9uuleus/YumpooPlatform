package com.yumpoo.platform.identityaccess.application.account;

import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;

public interface AccountStatusUseCase {

    IdempotencyExecutionResult change(AccountStatusChangeCommand command);
}
