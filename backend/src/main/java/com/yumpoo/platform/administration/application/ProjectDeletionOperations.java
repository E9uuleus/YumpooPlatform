package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.catalog.api.ProjectDeletionCommandPort;
import com.yumpoo.platform.catalog.api.ProjectDeletionQuery;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ProjectDeletionOperations {
    private final ProjectDeletionCommandPort projects;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper json;
    private final ProjectDeletionSettings settings;
    private final Clock clock;
    public ProjectDeletionOperations(ProjectDeletionCommandPort projects, IdempotentCommandExecutor idempotency,
            TransactionalEventPort events, SecurityAuditAppendPort audits, ObjectMapper json,
            ProjectDeletionSettings settings, Clock clock) {
        this.projects = projects; this.idempotency = idempotency; this.events = events; this.audits = audits;
        this.json = json; this.settings = settings; this.clock = clock;
    }
    public StoredCommandResult schedule(ProjectDeletionOperation command) { return execute(command, true); }
    public StoredCommandResult cancel(ProjectDeletionOperation command) { return execute(command, false); }

    private StoredCommandResult execute(ProjectDeletionOperation command, boolean schedule) {
        String route = schedule ? "scheduleProjectDeletion" : "cancelProjectDeletion";
        var key = new IdempotencyCommand(new IdempotencyScope(command.actor().userId(),
                schedule ? "POST" : "DELETE", route, command.idempotencyKey()), command.requestHash());
        return idempotency.execute(key, () -> {
            var now = clock.instant();
            ProjectDeletionQuery.State project = schedule
                    ? projects.schedule(command.actor(), command.projectId(), command.expectedVersion(),
                        command.confirmationCode(), settings.gracePeriod(), now)
                    : projects.cancel(command.actor(), command.projectId(), command.expectedVersion(), now);
            String action = schedule ? "PROJECT_DELETION_SCHEDULED" : "PROJECT_DELETION_CANCELLED";
            Map<String, Object> payload = schedule
                    ? Map.of("projectId", project.projectId(), "requestedAt", project.requestedAt(),
                        "requestedBy", project.requestedBy(), "purgeAfter", project.purgeAfter())
                    : Map.of("projectId", project.projectId(), "cancelledAt", now, "cancelledBy", command.actor().userId());
            audits.append(new SecurityAuditDraft(project.companyId(), action.toLowerCase() + ":" + command.idempotencyKey(),
                    action, SecurityAuditOutcome.SUCCEEDED, SecurityAuditActor.user(command.actor().userId(),
                        ProjectLifecycleGovernanceService.roleNames(command.actor())), "PROJECT", project.projectId().toString(),
                    null, null, json.valueToTree(payload), null, command.idempotencyKey(), null, null, now));
            events.append(new EventDraft(schedule ? "catalog.project_deletion_scheduled" : "catalog.project_deletion_cancelled",
                    1, "Project", project.projectId(), project.rowVersion(), project.companyId(),
                    EventActor.user(command.actor().userId()), json.valueToTree(payload)));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", project.projectId()); response.put("lifecycle", project.lifecycle());
            response.put("rowVersion", project.rowVersion()); response.put("etag", StrongEtag.format(project.rowVersion()));
            if (project.requestedAt() != null) response.put("deletion", Map.of("requestedAt", project.requestedAt(),
                    "requestedBy", project.requestedBy(), "purgeAfter", project.purgeAfter()));
            return new StoredCommandResult(200, json.writeValueAsString(response), project.projectId(), StrongEtag.format(project.rowVersion()));
        }).result();
    }
}
