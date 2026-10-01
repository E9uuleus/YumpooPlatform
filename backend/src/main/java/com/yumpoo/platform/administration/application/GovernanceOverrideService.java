package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.catalog.api.ProjectSnapshot;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.SafeBlocker;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GovernanceOverrideService {
    private final ProjectLifecycleGovernanceService projectLifecycle;
    private final GovernanceOverrideRepository repository;
    private final IdempotentCommandExecutor idempotency;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GovernanceOverrideService(ProjectLifecycleGovernanceService projectLifecycle,
            GovernanceOverrideRepository repository,
            IdempotentCommandExecutor idempotency, SecurityAuditAppendPort audits,
            ObjectMapper objectMapper, Clock clock) {
        this.projectLifecycle = projectLifecycle;
        this.repository = repository; this.idempotency = idempotency; this.audits = audits;
        this.objectMapper = objectMapper; this.clock = clock;
    }

    @Transactional
    public IdempotencyExecutionResult override(GovernanceOverrideCommand command) {
        ProjectLifecycleGovernanceService.requireAdmin(command.actor());
        String reason = ProjectLifecycleGovernanceService.validateReason(command.reason());
        IdempotencyCommand key = new IdempotencyCommand(new IdempotencyScope(command.actor().userId(),
                "POST", "governanceOverride:" + command.action().name(), command.idempotencyKey()),
                command.requestHash());
        return idempotency.execute(key, () -> {
            try {
                return execute(command, reason);
            } catch (ApplicationException exception) {
                if (exception.errorCode() == StandardErrorCode.DEPENDENCY_UNAVAILABLE
                        || exception.errorCode() == StandardErrorCode.INTERNAL_ERROR) {
                    throw exception;
                }
                return stableFailure(command, reason, exception);
            }
        });
    }

    @Transactional(readOnly = true)
    public GovernanceOverridePage findAll(CurrentActor actor, GovernanceOverrideAction action,
            String targetType, UUID targetId, GovernanceOverrideResult result, int offset, int size) {
        ProjectLifecycleGovernanceService.requireAdmin(actor);
        if (offset < 0 || size < 1 || size > 100) {
            throw new ApplicationException(StandardErrorCode.VALIDATION_FAILED);
        }
        return new GovernanceOverridePage(repository.findAll(actor.companyId(), action,
                targetType, targetId, result, offset, size), offset, size,
                repository.count(actor.companyId(), action, targetType, targetId, result));
    }

    private StoredCommandResult execute(GovernanceOverrideCommand command, String reason) {
        return switch (command.action()) {
            case PROJECT_ARCHIVE_WITH_OPEN_ITEMS -> archiveProject(command, reason);
            case WORKSPACE_ARCHIVE_WITH_ACTIVE_PROJECTS -> throw new ApplicationException(
                    StandardErrorCode.VALIDATION_FAILED);
        };
    }

    private StoredCommandResult archiveProject(GovernanceOverrideCommand command, String reason) {
        requireTargetType(command, "PROJECT");
        ProjectSnapshot before = projectLifecycle.lockForOverride(command.actor(), command.targetId(),
                command.expectedRowVersion());
        List<SafeBlocker> blockers = projectLifecycle.blockers(before);
        ProjectSnapshot after = projectLifecycle.archiveOverride(command.actor(), command.targetId(),
                command.expectedRowVersion(), command.idempotencyKey(), reason, blockers);
        insert(command, reason, "PROJECT", safe(before), safe(after), blockers,
                GovernanceOverrideResult.SUCCEEDED, null);
        return projectLifecycle.stored(after);
    }

    private StoredCommandResult stableFailure(GovernanceOverrideCommand command, String reason,
            ApplicationException exception) {
        JsonNode safe = objectMapper.valueToTree(Map.of("targetId", command.targetId(),
                "expectedRowVersion", command.expectedRowVersion()));
        insert(command, reason, command.targetType(), safe, null, exception.blockers(),
                GovernanceOverrideResult.FAILED, exception.errorCode().name());
        audits.append(new SecurityAuditDraft(command.actor().companyId(), "governance-override-failed:"
                + command.idempotencyKey(), "GOVERNANCE_OVERRIDE", SecurityAuditOutcome.FAILED,
                SecurityAuditActor.user(command.actor().userId(),
                        ProjectLifecycleGovernanceService.roleNames(command.actor())), command.targetType(),
                command.targetId().toString(), reason, safe, null, exception.errorCode().name(),
                command.idempotencyKey(), null, null, clock.instant()));
        int status = status(exception.errorCode());
        Map<String, Object> details = Map.of("reason", exception.reason() == null ? "OVERRIDE_FAILED" : exception.reason(),
                "blockers", exception.blockers());
        return jsonResult(status, Map.of("code", exception.errorCode().name(),
                "message", exception.getMessage(), "requestId", command.idempotencyKey().toString(),
                "retryable", false, "fieldErrors", exception.fieldViolations(), "details", details),
                command.targetId(), null);
    }

    private void insert(GovernanceOverrideCommand command, String reason, String targetType,
            JsonNode before, JsonNode after, List<SafeBlocker> blockers,
            GovernanceOverrideResult result, String errorCode) {
        repository.insert(new GovernanceOverrideRecord(UUID.randomUUID(), command.actor().companyId(),
                command.action(), targetType, command.targetId(), reason, command.requestHash().value(),
                command.idempotencyKey(), command.actor().userId(), before,
                after, objectMapper.valueToTree(blockers), result, errorCode, clock.instant()));
    }

    private static void requireTargetType(GovernanceOverrideCommand command, String expected) {
        if (!expected.equals(command.targetType())) {
            throw new ApplicationException(StandardErrorCode.VALIDATION_FAILED);
        }
    }

    private JsonNode safe(ProjectSnapshot project) {
        return objectMapper.valueToTree(ProjectLifecycleGovernanceService.safeSnapshot(project));
    }

    private StoredCommandResult jsonResult(int status, Object body, UUID resourceId, String etag) {
        try { return new StoredCommandResult(status, objectMapper.writeValueAsString(body), resourceId, etag); }
        catch (JacksonException e) { throw new IllegalStateException("override response serialization failed", e); }
    }

    private static int status(StandardErrorCode code) {
        return switch (code) {
            case MALFORMED_REQUEST -> 400;
            case AUTHENTICATION_REQUIRED -> 401;
            case ACCOUNT_DISABLED, ACCESS_DENIED -> 403;
            case RESOURCE_NOT_FOUND -> 404;
            case IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS, INVALID_STATE_TRANSITION, WORKLOG_LOCKED -> 409;
            case VERSION_CONFLICT -> 412;
            case FILE_TOO_LARGE -> 413;
            case FILE_TYPE_NOT_ALLOWED -> 415;
            case VALIDATION_FAILED -> 422;
            case CLIENT_UPGRADE_REQUIRED -> 426;
            case PRECONDITION_REQUIRED -> 428;
            case RATE_LIMITED -> 429;
            case INTERNAL_ERROR -> 500;
            case DEPENDENCY_UNAVAILABLE -> 503;
        };
    }
}
