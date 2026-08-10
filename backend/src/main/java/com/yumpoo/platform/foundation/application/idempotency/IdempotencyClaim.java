package com.yumpoo.platform.foundation.application.idempotency;

import java.util.Objects;
import java.util.UUID;

public sealed interface IdempotencyClaim permits IdempotencyClaim.Acquired, IdempotencyClaim.Existing {

    record Acquired(UUID recordId) implements IdempotencyClaim {

        public Acquired {
            Objects.requireNonNull(recordId, "recordId must not be null");
        }
    }

    record Existing(IdempotencyRecord record) implements IdempotencyClaim {

        public Existing {
            Objects.requireNonNull(record, "record must not be null");
        }
    }
}
