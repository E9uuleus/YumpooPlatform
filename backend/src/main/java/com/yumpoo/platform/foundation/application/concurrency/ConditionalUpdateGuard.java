package com.yumpoo.platform.foundation.application.concurrency;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 校验单聚合条件更新的影响行数，并把零行结果转换为统一应用错误。
 */
public final class ConditionalUpdateGuard {

    private ConditionalUpdateGuard() {
    }

    public static void requireSingleRowUpdated(
            int updatedRows,
            Supplier<ConditionalUpdateFailure> failureSupplier
    ) {
        if (updatedRows != 0 && updatedRows != 1) {
            throw new IllegalStateException("conditional update must affect zero or one row, but affected "
                    + updatedRows);
        }
        if (updatedRows == 1) {
            return;
        }

        Objects.requireNonNull(failureSupplier, "failureSupplier must not be null");
        ConditionalUpdateFailure failure = Objects.requireNonNull(
                failureSupplier.get(),
                "failureSupplier must return a failure"
        );
        throw new ApplicationException(errorCodeFor(failure));
    }

    private static StandardErrorCode errorCodeFor(ConditionalUpdateFailure failure) {
        return switch (failure) {
            case RESOURCE_NOT_VISIBLE -> StandardErrorCode.RESOURCE_NOT_FOUND;
            case VERSION_CONFLICT -> StandardErrorCode.VERSION_CONFLICT;
            case INVALID_STATE -> StandardErrorCode.INVALID_STATE_TRANSITION;
        };
    }
}
