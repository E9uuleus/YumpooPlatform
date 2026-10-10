package com.yumpoo.platform.catalog.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectPurgeQueue {
    record Lease(UUID companyId, UUID projectId, String stage, UUID token, Instant until) {}
    record Reminder(UUID companyId, UUID projectId, long rowVersion, Instant purgeAfter) {}
    Optional<Reminder> remindOne(Instant now, Duration reminderLead);
    default Optional<Lease> claim(Instant now,String worker,Duration duration) { return claim(now,worker,duration,false); }
    Optional<Lease> claim(Instant now,String worker,Duration duration,boolean newFirst);
    boolean hold(Lease lease,Instant now);
    boolean advance(Lease lease, String nextStage, Instant now);
    boolean release(Lease lease, Instant now);
    Optional<Long> complete(Lease lease, Instant now);
}
