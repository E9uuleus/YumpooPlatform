package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.catalog.api.ProjectArchiveMutation;
import com.yumpoo.platform.catalog.api.ProjectLifecycleCommandPort;
import com.yumpoo.platform.catalog.api.ProjectRestoreMutation;
import com.yumpoo.platform.catalog.api.ProjectRestoreSnapshot;
import com.yumpoo.platform.catalog.api.ProjectSnapshot;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.SafeBlocker;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshot;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionQuery;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public final class ProjectLifecycleGovernanceService {
    private final ProjectLifecycleCommandPort projects;
    private final ProjectArchiveBlockerCollector blockers;
    private final ActiveUserSnapshotQuery users;
    private final ProjectTemplateVersionQuery templates;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProjectLifecycleGovernanceService(ProjectLifecycleCommandPort projects,
            ProjectArchiveBlockerCollector blockers, ActiveUserSnapshotQuery users,
            ProjectTemplateVersionQuery templates, IdempotentCommandExecutor idempotency,
            TransactionalEventPort events, SecurityAuditAppendPort audits,
            ObjectMapper objectMapper, Clock clock) {
        this.projects = projects; this.blockers = blockers; this.users = users;
        this.templates = templates; this.idempotency = idempotency; this.events = events;
        this.audits = audits; this.objectMapper = objectMapper; this.clock = clock;
    }

    public IdempotencyExecutionResult archive(ProjectArchiveOperationCommand command) {
        return idempotency.execute(key(command.actor(), "archiveProject", command.idempotencyKey(),
                command.requestHash()), () -> {
            ProjectArchiveMutation mutation = new ProjectArchiveMutation(command.actor().companyId(),
                    command.projectId(), command.expectedRowVersion(), command.actor().userId(), true);
            ProjectSnapshot before = projects.lockForArchive(mutation);
            List<SafeBlocker> found = blockers.collect(before.companyId(), before.projectId());
            if (!found.isEmpty()) {
                throw ApplicationException.withBlockers(StandardErrorCode.INVALID_STATE_TRANSITION,
                        "PROJECT_ARCHIVE_BLOCKED", found);
            }
            ProjectSnapshot after = projects.archive(mutation);
            appendArchive(before, after, command.actor(), "NORMAL", found, command.idempotencyKey(), null);
            return stored(after);
        });
    }

    public IdempotencyExecutionResult restore(ProjectRestoreOperationCommand command) {
        requireAdmin(command.actor());
        return idempotency.execute(key(command.actor(), "restoreProject", command.idempotencyKey(),
                command.requestHash()), () -> {
            ProjectRestoreMutation mutation = new ProjectRestoreMutation(command.actor().companyId(),
                    command.projectId(), command.expectedRowVersion(), command.actor().userId());
            ProjectRestoreSnapshot locked = projects.lockForRestore(mutation);
            requireRestoreReady(locked);
            ProjectSnapshot before = locked.project();
            ProjectSnapshot after = projects.reopen(mutation);
            appendLifecycle("PROJECT_REOPENED", "catalog.project_reopened", before, after,
                    command.actor(), command.idempotencyKey(), null);
            return stored(after);
        });
    }

    ProjectSnapshot archiveOverride(CurrentActor actor, UUID projectId, long version,
            UUID idempotencyKey, String reason, List<SafeBlocker> found) {
        ProjectArchiveMutation mutation = new ProjectArchiveMutation(actor.companyId(), projectId,
                version, actor.userId(), false);
        ProjectSnapshot before = projects.lockForArchive(mutation);
        ProjectSnapshot after = projects.archive(mutation);
        appendArchive(before, after, actor, "GOVERNANCE_OVERRIDE", found, idempotencyKey, reason);
        return after;
    }

    ProjectSnapshot lockForOverride(CurrentActor actor, UUID projectId, long version) {
        return projects.lockForArchive(new ProjectArchiveMutation(actor.companyId(), projectId,
                version, actor.userId(), false));
    }

    List<SafeBlocker> blockers(ProjectSnapshot project) {
        return blockers.collect(project.companyId(), project.projectId());
    }

    StoredCommandResult stored(ProjectSnapshot project) {
        try {
            return new StoredCommandResult(200, objectMapper.writeValueAsString(response(project)),
                    project.projectId(), StrongEtag.format(project.rowVersion()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("project lifecycle response serialization failed", exception);
        }
    }

    private void requireRestoreReady(ProjectRestoreSnapshot locked) {
        ProjectSnapshot project = locked.project();
        ActiveUserSnapshot owner = users.findByUserId(project.ownerUserId()).orElse(null);
        if (owner == null || !owner.companyId().equals(project.companyId())
                || !owner.activeAndEnabled() || !locked.ownerMembershipActive()) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "OWNER_MISSING");
        }
        ProjectTemplateSnapshot template = templates.findAny(project.templateKey(),
                project.templateVersion()).orElse(null);
        if (template == null || !template.projectType().equals(project.projectType())
                || !("PUBLISHED".equals(template.lifecycleStatus())
                || "RETIRED".equals(template.lifecycleStatus()))) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "TEMPLATE_UNAVAILABLE");
        }
    }

    private void appendArchive(ProjectSnapshot before, ProjectSnapshot after, CurrentActor actor,
            String mode, List<SafeBlocker> blockers, UUID commandId, String reason) {
        Map<String, Object> payload = lifecycleSummary(before, after);
        payload.put("mode", mode);
        payload.put("blockers", blockers.stream().map(value -> Map.of(
                "code", value.code(), "count", value.count())).toList());
        append("GOVERNANCE_OVERRIDE".equals(mode) ? "PROJECT_ARCHIVE_OVERRIDE" : "PROJECT_ARCHIVED",
                "catalog.project_archived", before, after, actor,
                commandId, reason, payload);
    }

    private void appendLifecycle(String auditAction, String eventType, ProjectSnapshot before,
            ProjectSnapshot after, CurrentActor actor, UUID commandId, String reason) {
        append(auditAction, eventType, before, after, actor, commandId, reason,
                lifecycleSummary(before, after));
    }

    private void append(String auditAction, String eventType, ProjectSnapshot before,
            ProjectSnapshot after, CurrentActor actor, UUID commandId, String reason,
            Map<String, Object> payload) {
        audits.append(new SecurityAuditDraft(after.companyId(), auditAction.toLowerCase() + ":" + commandId,
                auditAction, SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(actor.userId(), roleNames(actor)), "PROJECT",
                after.projectId().toString(), reason, objectMapper.valueToTree(safeSnapshot(before)),
                objectMapper.valueToTree(safeSnapshot(after)), null, commandId, null, null, clock.instant()));
        events.append(new EventDraft(eventType, 1, "Project", after.projectId(), after.rowVersion(),
                after.companyId(), EventActor.user(actor.userId()), objectMapper.valueToTree(payload)));
    }

    private static Map<String, Object> lifecycleSummary(ProjectSnapshot before, ProjectSnapshot after) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", after.projectId()); result.put("code", after.code());
        result.put("fromLifecycle", before.lifecycle()); result.put("toLifecycle", after.lifecycle());
        result.put("rowVersion", after.rowVersion()); return result;
    }

    static Map<String, Object> safeSnapshot(ProjectSnapshot project) {
        return Map.of("projectId", project.projectId(), "workspaceId", project.workspaceId(),
                "lifecycle", project.lifecycle(), "rowVersion", project.rowVersion());
    }

    private static Map<String, Object> response(ProjectSnapshot p) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", p.projectId()); result.put("workspaceId", p.workspaceId());
        result.put("code", p.code()); result.put("name", p.name()); result.put("description", p.description());
        result.put("projectType", p.projectType()); result.put("lifecycle", p.lifecycle());
        result.put("ownerUserId", p.ownerUserId()); result.put("templateKey", p.templateKey());
        result.put("templateVersion", p.templateVersion()); result.put("customerName", p.customerName());
        result.put("customerReference", p.customerReference()); result.put("deliverySite", p.deliverySite());
        result.put("contactNote", p.contactNote()); result.put("rowVersion", p.rowVersion()); return result;
    }

    private static IdempotencyCommand key(CurrentActor actor, String route, UUID id,
            com.yumpoo.platform.foundation.application.idempotency.RequestHash hash) {
        return new IdempotencyCommand(new IdempotencyScope(actor.userId(), "POST", route, id), hash);
    }

    static String validateReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        if (normalized.length() < 10 || normalized.length() > 500) {
            throw ApplicationException.validation(new FieldViolation("reason", "SIZE",
                    "理由长度必须为 10–500 个字符"));
        }
        return normalized;
    }

    static void requireAdmin(CurrentActor actor) {
        if (actor == null || !actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
    }

    static Set<String> roleNames(CurrentActor actor) {
        return actor.platformRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
