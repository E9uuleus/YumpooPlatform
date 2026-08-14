package com.yumpoo.platform.identityaccess.application.authorization;

import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;

public interface PlatformRoleManagementUseCase {
    IdempotencyExecutionResult grant(GrantPlatformRoleCommand command);

    IdempotencyExecutionResult revoke(RevokePlatformRoleCommand command);
}
