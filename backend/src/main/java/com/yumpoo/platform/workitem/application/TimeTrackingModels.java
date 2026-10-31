package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TimeTrackingModels {
    private TimeTrackingModels() {}

    /** Exactly one of workItemId (with its projectId) and timeCategory is set; {@code migrated} marks work item records written by the migrated-time import. */
    public record Session(UUID id, UUID companyId, UUID projectId, UUID workItemId,
            UUID userId, Instant startedAt, Instant stoppedAt, String source,
            long rowVersion, Instant deletedAt, String changeReason, TimeCategory timeCategory, UUID categoryProjectId, boolean migrated) {
        public Session(UUID id, UUID companyId, UUID projectId, UUID workItemId, UUID userId, Instant startedAt,
                Instant stoppedAt, String source, long rowVersion, Instant deletedAt, String changeReason) {
            this(id,companyId,projectId,workItemId,userId,startedAt,stoppedAt,source,rowVersion,deletedAt,changeReason,null,null,false);
        }
        public Session(UUID id, UUID companyId, UUID projectId, UUID workItemId, UUID userId, Instant startedAt, Instant stoppedAt,
                String source, long rowVersion, Instant deletedAt, String changeReason, TimeCategory timeCategory, UUID categoryProjectId) {
            this(id,companyId,projectId,workItemId,userId,startedAt,stoppedAt,source,rowVersion,deletedAt,changeReason,timeCategory,categoryProjectId,false);
        }
        public static Session create(UUID companyId, UUID projectId, TimeSubject subject, UUID userId,
                Instant startedAt, Instant stoppedAt, String source) {
            return new Session(UUID.randomUUID(),companyId,projectId,subject.workItemId(),userId,startedAt,stoppedAt,source,0,null,null,subject.timeCategory(),subject.categoryProjectId());
        }
        /** A work item record of the migrated-time import. */
        public static Session createMigrated(UUID companyId, UUID projectId, UUID workItemId, UUID userId, Instant startedAt, Instant stoppedAt) {
            return new Session(UUID.randomUUID(),companyId,projectId,workItemId,userId,startedAt,stoppedAt,"MANUAL",0,null,null,null,null,true);
        }
        public TimeSubject subject() { return TimeSubject.of(workItemId,timeCategory,categoryProjectId); }
        /** The same session at its next row version; project, work item and category never change in place. */
        public Session next(Instant start, Instant stop, Instant deleted, String reason) {
            return new Session(id,companyId,projectId,workItemId,userId,start,stop,source,rowVersion+1,deleted,reason,timeCategory,categoryProjectId,migrated);
        }
        /** Written by the migrated-time import, as a legacy MIGRATED category record or a flagged work item record; members cannot change either. */
        public boolean migratedImport() { return migrated || timeCategory==TimeCategory.MIGRATED; }
    }

    public record TimeTrackingSession(UUID id, UUID projectId, UUID workItemId,
            UUID userId, String displayName, Instant startedAt, Instant stoppedAt,
            String source, long durationMs, long rowVersion, String etag,
            boolean canEdit, boolean deleted, String changeReason, long allocatedDurationMs, int parallelItemCount,
            String workItemNo, String workItemTitle, TimeCategory timeCategory, UUID categoryProjectId, boolean migrated) {}

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

    public record TimeSessionFillEntry(UUID workItemId, Instant startedAt, Instant stoppedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) TimeCategory timeCategory,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID categoryProjectId) {
        public static TimeSessionFillEntry of(TimeSubject subject, Instant startedAt, Instant stoppedAt) {
            return new TimeSessionFillEntry(subject.workItemId(),startedAt,stoppedAt,subject.timeCategory(),subject.categoryProjectId());
        }
        public TimeSubject subject() { return TimeSubject.of(workItemId,timeCategory,categoryProjectId); }
    }

    public record TimeSessionFillCommand(TimeSessionFillMode mode, List<TimeSessionFillEntry> entries) {}

    public record TimeSessionFillResult(int createdCount, int changedCount, int deletedCount, long skippedDurationMs) {}

    public record TimeSessionReassignCommand(UUID workItemId, @JsonInclude(JsonInclude.Include.NON_NULL) TimeCategory timeCategory,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID categoryProjectId) {}

    public record TimeCategorySubject(TimeCategory timeCategory, UUID projectId) {}

    public record RecentTimeTrackingItem(UUID workItemId, UUID projectId, String title) {}

    /** completed 按本人对该项目的完成定义计算，未设置时 DONE 与 CANCELED 两类状态视为完成。 */
    public record TimerCandidate(UUID workItemId, UUID projectId, String projectName, String itemNo,
            String title, String contentName, String contentCode, String contentColorToken, String statusCategory,
            String statusCode, String statusName, String statusColorToken, boolean completed,
            boolean assignedToMe, Instant lastTrackedAt, long ownDurationMs,
            UUID parentWorkItemId, String parentTitle, boolean parentCompleted) {
        public TimerCandidate withProjectName(String name) {
            return new TimerCandidate(workItemId, projectId, name, itemNo, title, contentName, contentCode, contentColorToken,
                    statusCategory, statusCode, statusName, statusColorToken, completed, assignedToMe, lastTrackedAt, ownDurationMs,
                    parentWorkItemId, parentTitle, parentCompleted);
        }
    }

    public record TimerCandidateProjectCount(UUID projectId, String projectName, long count) {}

    public record TimerCandidatePage(List<TimerCandidate> items, Integer nextOffset,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<TimerCandidateProjectCount> projects,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer hiddenCompletedCount) {}

    public record CurrentRunningTimer(TimeTrackingSession session, String workItemTitle) {}

    /** 工作项表格中已完成项的显示：置灰、置灰加删除线，或主表不显示。 */
    public enum HideCompletedEffect { FADE, FADE_STRIKETHROUGH, HIDE }

    public record TimeTrackingPreferences(boolean timerEnabled, int workdayStartMinute, int workdayEndMinute,
            boolean hideCompleted, HideCompletedEffect hideCompletedEffect) {
        public static final TimeTrackingPreferences DEFAULT=new TimeTrackingPreferences(true,540,1080,false,HideCompletedEffect.FADE_STRIKETHROUGH);
    }

    /** hideCompleted 与 hideCompletedEffect 省略时保留原值。 */
    public record TimeTrackingPreferencesCommand(Boolean timerEnabled, Integer workdayStartMinute, Integer workdayEndMinute,
            Boolean hideCompleted, HideCompletedEffect hideCompletedEffect) {}

    public record CurrentTimeTracker(TimeTrackingSession session, String workItemTitle,
            long rowVersion, String etag, Instant serverNow, List<RecentTimeTrackingItem> recentItems,
            List<CurrentRunningTimer> runningSessions, List<RecentTimeTrackingItem> resumeItems,
            List<TimeCategory> resumeCategories, List<TimeCategorySubject> resumeCategorySubjects, TimeTrackingPreferences preferences) {
        public static CurrentTimeTracker of(List<CurrentRunningTimer> running, long version, Instant now,
                List<RecentTimeTrackingItem> recentItems, List<RecentTimeTrackingItem> resumeItems,
                List<TimeCategory> resumeCategories, List<TimeCategorySubject> resumeCategorySubjects, TimeTrackingPreferences preferences) {
            var first=running.isEmpty() ? null : running.getFirst();
            return new CurrentTimeTracker(first==null ? null : first.session(),first==null ? null : first.workItemTitle(),
                    version,StrongEtag.format(version),now,recentItems,running,resumeItems,resumeCategories,resumeCategorySubjects,preferences);
        }
    }
}
