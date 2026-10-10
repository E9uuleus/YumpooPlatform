package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Raw time and open assignments; callers pass project ids already resolved inside the company. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TeamWorkService {
    private final TeamWorkRepository repository;
    public TeamWorkService(TeamWorkRepository repository) { this.repository = repository; }

    public List<TeamWorkRepository.TimeEntry> timeEntries(CurrentActor actor, List<UUID> projectIds, List<UUID> userIds,
            LocalDate from, LocalDate to, ZoneId zone, Instant asOf) {
        requireAdministrator(actor);
        return projectIds.isEmpty() ? List.of() : repository.timeEntries(actor.companyId(), projectIds, userIds, from, to, zone, asOf);
    }
    public List<TeamWorkRepository.Load> currentLoad(CurrentActor actor, List<UUID> projectIds, LocalDate today) {
        requireAdministrator(actor);
        return projectIds.isEmpty() ? List.of() : repository.currentLoad(actor.companyId(), projectIds, today);
    }
    public TaskPage currentTasks(CurrentActor actor, List<UUID> projectIds, UUID userId, LocalDate today, int offset, int limit) {
        requireAdministrator(actor);
        return tasks(actor.companyId(), projectIds, userId, today, offset, limit);
    }
    public List<TeamWorkRepository.TimeEntry> ownTimeEntries(CurrentActor actor, List<UUID> projectIds,
            LocalDate from, LocalDate to, ZoneId zone, Instant asOf) {
        return projectIds.isEmpty() ? List.of()
                : repository.timeEntries(actor.companyId(), projectIds, List.of(actor.userId()), from, to, zone, asOf);
    }
    public TaskPage ownCurrentTasks(CurrentActor actor, List<UUID> projectIds, LocalDate today, int offset, int limit) {
        return tasks(actor.companyId(), projectIds, actor.userId(), today, offset, limit);
    }
    public record TaskPage(List<TeamWorkRepository.Task> items, long totalElements) {}

    private TaskPage tasks(UUID companyId, List<UUID> projectIds, UUID userId, LocalDate today, int offset, int limit) {
        if (projectIds.isEmpty()) return new TaskPage(List.of(), 0);
        return new TaskPage(repository.currentTasks(companyId, projectIds, userId, today, offset, limit),
                repository.countCurrentTasks(companyId, projectIds, userId));
    }
    private static void requireAdministrator(CurrentActor actor) {
        if (actor == null || !actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
    }
}
