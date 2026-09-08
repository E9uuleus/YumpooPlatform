package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Actor-scoped raw facts for worklog consumers; corrections remain owned by workitem. */
public interface TimeTrackingRecordQuery {
    Page findVisible(CurrentActor actor, UUID workItemId, String cursor);

    record Record(UUID id, UUID projectId, UUID workItemId, UUID userId,
                  Instant startedAt, Instant stoppedAt, String source, long durationMs, long version) {}
    record Page(List<Record> items, String nextCursor, Instant asOf) {}
}
