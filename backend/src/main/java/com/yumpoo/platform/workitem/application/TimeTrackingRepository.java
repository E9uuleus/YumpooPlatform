package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.yumpoo.platform.workitem.application.TimeTrackingModels.*;

public interface TimeTrackingRepository {
    List<TimerCandidate> candidates(UUID companyId, UUID userId, Collection<UUID> projectIds,
            Collection<UUID> matchingProjectIds, String query, boolean personal, int offset, int limit, Instant now);
    List<RecentTimeTrackingItem> recentItems(UUID companyId, UUID userId);
    List<UUID> visibleItemIds(UUID companyId, UUID projectId, Collection<UUID> ids);
    long stateVersion(UUID companyId, UUID userId, boolean lock);
    void advanceState(UUID companyId, UUID userId);
    Optional<Session> running(UUID companyId, UUID userId);
    Optional<Session> find(UUID companyId, UUID sessionId);
    List<Session> history(UUID companyId, UUID workItemId, Instant before, UUID beforeId, int limit);
    boolean overlaps(UUID companyId, UUID userId, Instant start, Instant stop, UUID excludedId);
    void save(Session session);
    void advanceProjects(UUID companyId, Collection<UUID> projectIds);
    long revision(UUID companyId, UUID projectId);
    List<TimeTrackingSummary> summaries(UUID companyId, UUID projectId,
            UUID userId, Collection<UUID> workItemIds, Instant now);
}
