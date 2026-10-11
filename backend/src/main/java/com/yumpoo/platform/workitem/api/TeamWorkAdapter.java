package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.TeamWorkRepository;
import com.yumpoo.platform.workitem.application.TeamWorkService;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Component
public class TeamWorkAdapter implements TeamWorkQuery {
    private final TeamWorkService service;
    public TeamWorkAdapter(TeamWorkService service) { this.service = service; }

    public List<TimeEntry> timeEntries(CurrentActor actor, List<UUID> projectIds, List<UUID> userIds, LocalDate from, LocalDate to, ZoneId zone, Instant asOf) {
        return entries(service.timeEntries(actor, projectIds, userIds, from, to, zone, asOf));
    }
    public List<Load> currentLoad(CurrentActor actor, List<UUID> projectIds, LocalDate today) {
        return service.currentLoad(actor, projectIds, today).stream().map(l -> new Load(l.userId(), l.todo(), l.inProgress(), l.overdue())).toList();
    }
    public TaskPage currentTasks(CurrentActor actor, List<UUID> projectIds, UUID userId, LocalDate today, int offset, int limit) {
        return tasks(service.currentTasks(actor, projectIds, userId, today, offset, limit));
    }
    public List<TimeEntry> ownTimeEntries(CurrentActor actor, List<UUID> projectIds, LocalDate from, LocalDate to, ZoneId zone, Instant asOf) {
        return entries(service.ownTimeEntries(actor, projectIds, from, to, zone, asOf));
    }
    public TaskPage ownCurrentTasks(CurrentActor actor, List<UUID> projectIds, LocalDate today, int offset, int limit) {
        return tasks(service.ownCurrentTasks(actor, projectIds, today, offset, limit));
    }

    private static List<TimeEntry> entries(List<TeamWorkRepository.TimeEntry> entries) {
        return entries.stream().map(e -> new TimeEntry(e.userId(), e.workItemId(), e.projectId(), e.itemNo(), e.title(), e.date(), e.durationMs())).toList();
    }
    private static TaskPage tasks(TeamWorkService.TaskPage page) {
        return new TaskPage(page.items().stream().map(t -> new Task(t.id(), t.projectId(), t.itemNo(), t.title(), t.statusName(), t.statusCategory(),
                t.statusColor(), t.priorityName(), t.priorityColor(), t.dueDate(), t.overdue())).toList(), page.totalElements());
    }
}
