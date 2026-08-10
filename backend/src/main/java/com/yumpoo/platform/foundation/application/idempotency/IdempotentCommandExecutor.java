package com.yumpoo.platform.foundation.application.idempotency;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 在一个数据库事务中完成幂等占位、业务命令和可重放结果保存。
 */
@Service
@Transactional
public class IdempotentCommandExecutor {

    /** 仅写入一期恢复元数据；M0-10 不执行超时接管。 */
    static final Duration PROCESSING_RECOVERY_METADATA_WINDOW = Duration.ofMinutes(1);
    /** 仅写入一期清理元数据；M0-10 不执行自动清理。 */
    static final Duration DEFAULT_RETENTION_METADATA = Duration.ofDays(7);

    private final IdempotencyRecordPort recordPort;
    private final Clock clock;

    public IdempotentCommandExecutor(IdempotencyRecordPort recordPort, Clock clock) {
        this.recordPort = Objects.requireNonNull(recordPort, "recordPort must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public IdempotencyExecutionResult execute(
            IdempotencyCommand command,
            Supplier<StoredCommandResult> callback
    ) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        Instant claimedAt = clock.instant();
        UUID recordId = UUID.randomUUID();
        IdempotencyClaim claim = recordPort.claim(
                recordId,
                command,
                claimedAt,
                claimedAt.plus(PROCESSING_RECOVERY_METADATA_WINDOW),
                claimedAt.plus(DEFAULT_RETENTION_METADATA)
        );

        if (claim instanceof IdempotencyClaim.Acquired acquired) {
            StoredCommandResult result = Objects.requireNonNull(
                    callback.get(),
                    "callback result must not be null"
            );
            Instant completedAt = clock.instant();
            recordPort.complete(
                    acquired.recordId(),
                    result,
                    completedAt,
                    completedAt.plus(DEFAULT_RETENTION_METADATA)
            );
            return IdempotencyExecutionResult.executed(result);
        }

        IdempotencyRecord existing = ((IdempotencyClaim.Existing) claim).record();
        if (!existing.command().requestHash().equals(command.requestHash())) {
            throw new ApplicationException(StandardErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return switch (existing.state()) {
            case COMPLETED -> IdempotencyExecutionResult.replayed(existing.result());
            case PROCESSING -> throw new ApplicationException(StandardErrorCode.REQUEST_IN_PROGRESS);
        };
    }
}
