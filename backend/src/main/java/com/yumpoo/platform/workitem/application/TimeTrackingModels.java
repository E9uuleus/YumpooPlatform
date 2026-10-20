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
            boolean canEdit, boolean deleted, String changeReason, long allocatedDurationMs, int parallelItemCount,
            String workItemNo, String workItemTitle) {}

    public record RunningSession(UUID userId, Instant startedAt) {}

    public record TimeTrackingSummary(UUID workItemId, long completedDurationMs,
            long totalDurationMs, long ownDurationMs, long sessionCount,
            List<RunningSession> runningSessions, long allocatedDurationMs, long rollupDurationMs, double rollupRunningRate) {}

    public record SummaryPage(List<TimeTrackingSummary> items, Instant serverNow, long revision) {}

    public record SessionPage(List<TimeTrackingSession> items, String nextCursor,
            TimeTrackingSummary summary, Instant serverNow, boolean canCreate) {}

    public record MySessionRow(Session session, String itemNo, String title,
            String contentColorToken, boolean archived, boolean itemDeleted) {}

    public record MyTimeSession(TimeTrackingSession session, String projectName, String itemNo,
            String title, String contentColorToken) {}

    public record MyTimeSessionPage(List<MyTimeSession> items, Instant serverNow) {}

    public enum TimeSessionFillMode { PARALLEL, REPLACE, GAPS }

    public record TimeSessionFillEntry(UUID workItemId, Instant startedAt, Instant stoppedAt) {}

    public record TimeSessionFillCommand(TimeSessionFillMode mode, List<TimeSessionFillEntry> entries) {}

    public record TimeSessionFillResult(int createdCount, int changedCount, int deletedCount, long skippedDurationMs) {}

    public record TimeSessionReassignCommand(UUID workItemId) {}

    public record RecentTimeTrackingItem(UUID workItemId, UUID projectId, String title) {}

    /** completed 按本人对该项目的完成定义计算，未设置时 DONE 与 CANCELED 两类状态视为完成。 */
    public record TimerCandidate(UUID workItemId, UUID projectId, String projectName, String itemNo,
            String title, String contentName, String contentCode, String contentColorToken, String statusCategory, boolean completed,
            boolean assignedToMe, Instant lastTrackedAt, long ownDurationMs) {
        public TimerCandidate withProjectName(String name) {
            return new TimerCandidate(workItemId, projectId, name, itemNo, title, contentName, contentCode, contentColorToken,
                    statusCategory, completed, assignedToMe, lastTrackedAt, ownDurationMs);
        }
    }

    public record TimerCandidatePage(List<TimerCandidate> items, Integer nextOffset) {}

    public record CurrentRunningTimer(TimeTrackingSession session, String workItemTitle) {}

    public record TimeTrackingPreferences(boolean timerEnabled, int workdayStartMinute, int workdayEndMinute, boolean hideCompleted) {
        public static final TimeTrackingPreferences DEFAULT=new TimeTrackingPreferences(true,540,1080,false);
    }

    /** hideCompleted 省略时保留原值。 */
    public record TimeTrackingPreferencesCommand(Boolean timerEnabled, Integer workdayStartMinute, Integer workdayEndMinute, Boolean hideCompleted) {}

    public record CurrentTimeTracker(TimeTrackingSession session, String workItemTitle,
            long rowVersion, String etag, Instant serverNow, List<RecentTimeTrackingItem> recentItems,
            List<CurrentRunningTimer> runningSessions, List<RecentTimeTrackingItem> resumeItems,
            TimeTrackingPreferences preferences) {
        public static CurrentTimeTracker of(List<CurrentRunningTimer> running, long version, Instant now,
                List<RecentTimeTrackingItem> recentItems, List<RecentTimeTrackingItem> resumeItems, TimeTrackingPreferences preferences) {
            var first=running.isEmpty() ? null : running.getFirst();
            return new CurrentTimeTracker(first==null ? null : first.session(),first==null ? null : first.workItemTitle(),
                    version,StrongEtag.format(version),now,recentItems,running,resumeItems,preferences);
        }
    }
}
