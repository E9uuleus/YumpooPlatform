package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.catalog.api.ProjectActivationMutation;
import com.yumpoo.platform.catalog.api.ProjectActivationSnapshot;
import com.yumpoo.platform.catalog.api.ProjectLifecycleCommandPort;
import com.yumpoo.platform.catalog.api.ProjectSnapshot;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
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
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionQuery;
import com.yumpoo.platform.workitem.api.ProjectContentReadinessQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectActivationOrchestrator {

    private final ProjectLifecycleCommandPort projects;
    private final ActiveUserSnapshotQuery users;
    private final ProjectTemplateVersionQuery templates;
    private final ProjectContentReadinessQuery contents;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProjectActivationOrchestrator(ProjectLifecycleCommandPort projects,
            ActiveUserSnapshotQuery users, ProjectTemplateVersionQuery templates,
            ProjectContentReadinessQuery contents, IdempotentCommandExecutor idempotency,
            TransactionalEventPort events, SecurityAuditAppendPort audits,
            ObjectMapper objectMapper, Clock clock) {
        this.projects = projects;
        this.users = users;
        this.templates = templates;
        this.contents = contents;
        this.idempotency = idempotency;
        this.events = events;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyExecutionResult activate(ProjectActivationCommand command) {
        IdempotencyCommand key = new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "activateProject", command.idempotencyKey()),
                command.requestHash());
        return idempotency.execute(key, () -> execute(command));
    }

    private StoredCommandResult execute(ProjectActivationCommand command) {
        ProjectActivationMutation mutation = new ProjectActivationMutation(command.actor().companyId(),
                command.projectId(), command.expectedRowVersion(), command.actor().userId());
        ProjectActivationSnapshot locked = projects.lockForActivation(mutation);
        ProjectSnapshot before = locked.project();
        requireOwnerReady(before, locked.ownerMembershipActive());
        requireTemplateReady(before);
        if (!contents.hasActiveContent(before.companyId(), before.projectId(),
                before.templateKey(), before.templateVersion())) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "ACTIVE_CONTENT_MISSING");
        }
        if (!"PRODUCT_DEVELOPMENT".equals(before.projectType())
                && (before.customerName() == null || before.customerName().isBlank())) {
            throw ApplicationException.validation(new FieldViolation("customerName",
                    "REQUIRED_FOR_ACTIVATION", "非研发 Project 激活前必须填写客户名称"));
        }
        ProjectSnapshot after = projects.activate(mutation);
        Map<String, Object> summary = safeSummary(before, after);
        audits.append(new SecurityAuditDraft(after.companyId(),
                "project-activated:" + after.projectId(), "PROJECT_ACTIVATED",
                SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(command.actor().userId(), roleNames(command)),
                "PROJECT", after.projectId().toString(), null,
                objectMapper.valueToTree(Map.of("lifecycle", before.lifecycle(),
                        "rowVersion", before.rowVersion())), objectMapper.valueToTree(summary),
                null, command.idempotencyKey(), command.clientType(), command.clientVersion(),
                clock.instant()));
        events.append(new EventDraft("catalog.project_activated", 1, "Project", after.projectId(),
                after.rowVersion(), after.companyId(), EventActor.user(command.actor().userId()),
                objectMapper.valueToTree(summary)));
        return stored(after);
    }

    private void requireOwnerReady(ProjectSnapshot project, boolean membershipActive) {
        ActiveUserSnapshot owner = users.findByUserId(project.ownerUserId()).orElse(null);
        if (owner == null || !owner.companyId().equals(project.companyId())
                || !owner.activeAndEnabled() || !membershipActive) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "OWNER_MISSING");
        }
    }

    private void requireTemplateReady(ProjectSnapshot project) {
        ProjectTemplateSnapshot template = templates.findAny(project.templateKey(),
                project.templateVersion()).orElse(null);
        if (template == null || !template.projectType().equals(project.projectType())
                || !("PUBLISHED".equals(template.lifecycleStatus())
                || "RETIRED".equals(template.lifecycleStatus()))) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "TEMPLATE_UNAVAILABLE");
        }
    }

    private static Map<String, Object> safeSummary(ProjectSnapshot before, ProjectSnapshot after) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", after.projectId());
        result.put("code", after.code());
        result.put("fromLifecycle", before.lifecycle());
        result.put("toLifecycle", after.lifecycle());
        result.put("ownerUserId", after.ownerUserId());
        result.put("templateKey", after.templateKey());
        result.put("templateVersion", after.templateVersion());
        result.put("rowVersion", after.rowVersion());
        return result;
    }

    private StoredCommandResult stored(ProjectSnapshot project) {
        try {
            return new StoredCommandResult(200, objectMapper.writeValueAsString(response(project)),
                    project.projectId(), StrongEtag.format(project.rowVersion()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("project activation response serialization failed", exception);
        }
    }

    private static Map<String, Object> response(ProjectSnapshot project) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", project.projectId()); result.put("workspaceId", project.workspaceId());
        result.put("code", project.code()); result.put("name", project.name());
        result.put("description", project.description()); result.put("projectType", project.projectType());
        result.put("lifecycle", project.lifecycle()); result.put("ownerUserId", project.ownerUserId());
        result.put("templateKey", project.templateKey()); result.put("templateVersion", project.templateVersion());
        result.put("customerName", project.customerName());
        result.put("customerReference", project.customerReference());
        result.put("deliverySite", project.deliverySite()); result.put("contactNote", project.contactNote());
        result.put("rowVersion", project.rowVersion());
        return result;
    }

    private static Set<String> roleNames(ProjectActivationCommand command) {
        return command.actor().platformRoles().stream().map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
