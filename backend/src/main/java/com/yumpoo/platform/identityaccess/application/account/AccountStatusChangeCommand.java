package com.yumpoo.platform.identityaccess.application.account;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;

import java.util.Objects;
import java.util.UUID;

public record AccountStatusChangeCommand(
        UUID companyId,
        UUID targetUserId,
        AccountStatusCommandActor actor,
        AccountStatus desiredStatus,
        long expectedRowVersion,
        UUID idempotencyKey,
        RequestHash requestHash,
        String reason
) {

    public static AccountStatusChangeCommand disable(
            UUID companyId, UUID targetUserId, AccountStatusCommandActor actor,
            long expectedRowVersion, UUID idempotencyKey, RequestHash requestHash, String reason
    ) {
        return new AccountStatusChangeCommand(
                companyId, targetUserId, actor, AccountStatus.DISABLED,
                expectedRowVersion, idempotencyKey, requestHash, reason);
    }

    public static AccountStatusChangeCommand enable(
            UUID companyId, UUID targetUserId, AccountStatusCommandActor actor,
            long expectedRowVersion, UUID idempotencyKey, RequestHash requestHash, String reason
    ) {
        return new AccountStatusChangeCommand(
                companyId, targetUserId, actor, AccountStatus.ENABLED,
                expectedRowVersion, idempotencyKey, requestHash, reason);
    }

    public AccountStatusChangeCommand {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(desiredStatus, "desiredStatus must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        if (expectedRowVersion < 0) {
            throw ApplicationException.validation(new FieldViolation(
                    "expectedRowVersion",
                    "NON_NEGATIVE_REQUIRED",
                    "expectedRowVersion must not be negative"
            ));
        }
        if (reason == null || reason.isBlank()) {
            throw ApplicationException.validation(new FieldViolation(
                    "reason",
                    "REQUIRED",
                    "reason is required"
            ));
        }
        reason = reason.strip();
        if (reason.length() > 160) {
            throw ApplicationException.validation(new FieldViolation(
                    "reason",
                    "MAX_LENGTH",
                    "reason must contain at most 160 characters"
            ));
        }
    }
}
