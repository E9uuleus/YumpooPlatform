package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TimeTrackingModels {
    private TimeTrackingModels() {}

    public record Session(UUID id, UUID companyId, UUID projectId, UUID workItemId,
            UUID userId, Instant startedAt, Instant stoppedAt, String source,
            long rowVersion, Instant deletedAt, String changeReason) {}

    public record TimeTrackingSession(UUID id, UUID projectId, UUID workItemId,
            UUID userId, String displayName, Instant startedAt, Instant stoppedAt,
            String source, long durationMs, long rowVersion, String etag,
            boolean canEdit, boolean deleted, String changeReason) {}

    public record RunningSession(UUID userId, Instant startedAt) {}

    public record TimeTrackingSummary(UUID workItemId, long completedDurationMs,
            long totalDurationMs, long ownDurationMs, long sessionCount,
            List<RunningSession> runningSessions) {}

    public record SummaryPage(List<TimeTrackingSummary> items, Instant serverNow, long revision) {}

    public record SessionPage(List<TimeTrackingSession> items, String nextCursor,
            TimeTrackingSummary summary, Instant serverNow, boolean canCreate) {}

    public record RecentTimeTrackingItem(UUID workItemId, UUID projectId, String title) {}

    public record CurrentTimeTracker(TimeTrackingSession session, String workItemTitle,
            long rowVersion, String etag, Instant serverNow, List<RecentTimeTrackingItem> recentItems) {
        public static CurrentTimeTracker of(TimeTrackingSession session, String title,
                long version, Instant now, List<RecentTimeTrackingItem> recentItems) {
            return new CurrentTimeTracker(session, title, version, StrongEtag.format(version), now, recentItems);
        }
    }
}
