package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.*;
import com.yumpoo.platform.catalog.api.*;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.*;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProjectMembershipGovernanceService {
    private final ProjectMembershipQuery query;
    private final ProjectMembershipCommandPort commands;
    private final MinimalUserSnapshotQuery users;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProjectMembershipGovernanceService(ProjectMembershipQuery query,
            ProjectMembershipCommandPort commands, MinimalUserSnapshotQuery users,
            IdempotentCommandExecutor idempotency, TransactionalEventPort events,
            SecurityAuditAppendPort audits, ObjectMapper objectMapper, Clock clock) {
        this.query = query; this.commands = commands; this.users = users;
        this.idempotency = idempotency; this.events = events; this.audits = audits;
        this.objectMapper = objectMapper; this.clock = clock;
    }

    public IdempotencyExecutionResult add(ProjectMemberGovernanceCommand command) {
        return execute(command.actor(), "POST", "addProjectMember", command.idempotencyKey(), command.requestHash(), () -> {
            ProjectAccessSnapshot access = requireOwnerOrAdmin(command.actor(), command.projectId(), true);
            requireReasonForAdminPath(command.actor(), access, command.reason());
            requireAvailableUser(command.actor().companyId(), command.userId(), "userId");
            ProjectMemberMutationResult result = commands.add(new ProjectMemberMutation(
                    command.actor().companyId(), command.projectId(), command.userId(),
                    command.expectedMembershipVersion(), command.actor().userId(), normalized(command.reason())));
            appendMemberEvent("catalog.project_member_added", result.member(), command.actor(), "DIRECT");
            appendAudit("PROJECT_MEMBER_ADDED", result.member().membershipId(), command.actor(),
                    command.reason(), command.idempotencyKey(), command.clientType(), command.clientVersion(),
                    null, memberAudit(result.member()));
            return stored(result.created() ? 201 : 200, result.member(), result.member().etag());
        });
    }

    public IdempotencyExecutionResult remove(ProjectMemberGovernanceCommand command) {
        return execute(command.actor(), "DELETE", "removeProjectMember", command.idempotencyKey(), command.requestHash(), () -> {
            ProjectAccessSnapshot access = requireOwnerOrAdmin(command.actor(), command.projectId(), true);
            requireReasonForAdminPath(command.actor(), access, command.reason());
            ProjectMemberMutationResult result = commands.remove(new ProjectMemberMutation(
                    command.actor().companyId(), command.projectId(), command.userId(),
                    command.expectedMembershipVersion(), command.actor().userId(), normalized(command.reason())));
            appendMemberEvent("catalog.project_member_removed", result.member(), command.actor(), "DIRECT");
            appendAudit("PROJECT_MEMBER_REMOVED", result.member().membershipId(), command.actor(),
                    command.reason(), command.idempotencyKey(), command.clientType(), command.clientVersion(),
                    null, memberAudit(result.member()));
            return stored(200, result.member(), result.member().etag());
        });
    }

    public IdempotencyExecutionResult reassignOwner(ProjectOwnerReassignmentCommand command) {
        return execute(command.actor(), "POST", "reassignProjectOwner", command.idempotencyKey(), command.requestHash(), () -> {
            requireCompanyAdmin(command.actor());
            requireReason(command.reason());
            requireAvailableUser(command.actor().companyId(), command.newOwnerUserId(), "newOwnerUserId");
            ProjectOwnerReassignmentResult result = commands.reassignOwner(
                    new ProjectOwnerReassignmentMutation(command.actor().companyId(), command.projectId(),
                            command.expectedProjectVersion(), command.newOwnerUserId(), command.actor().userId()));
            if (result.membershipAdded()) {
                appendMemberEvent("catalog.project_member_added", result.ownerMembership(),
                        command.actor(), "OWNER_REASSIGNMENT");
            }
            Map<String,Object> payload = new LinkedHashMap<>();
            payload.put("projectId", result.after().projectId());
            payload.put("previousOwnerUserId", result.before().ownerUserId());
            payload.put("newOwnerUserId", result.after().ownerUserId());
            payload.put("lifecycle", result.after().lifecycle());
            events.append(new EventDraft("catalog.project_owner_reassigned", 1, "Project",
                    result.after().projectId(), result.after().rowVersion(), result.after().companyId(),
                    EventActor.user(command.actor().userId()), objectMapper.valueToTree(payload)));
            appendAudit("PROJECT_OWNER_REASSIGNED", result.after().projectId(), command.actor(),
                    command.reason(), command.idempotencyKey(), command.clientType(), command.clientVersion(),
                    Map.of("ownerUserId", result.before().ownerUserId(), "rowVersion", result.before().rowVersion()),
                    Map.of("ownerUserId", result.after().ownerUserId(), "rowVersion", result.after().rowVersion()));
            return stored(200, result.after(),
                    com.yumpoo.platform.foundation.application.concurrency.StrongEtag.format(result.after().rowVersion()));
        });
    }

    public void recordFailed(CurrentActor actor, String action, String targetType, String targetId,
                             String reason, UUID commandId, RuntimeException failure,
                             String clientType, String clientVersion) {
        String errorCode=failure instanceof ApplicationException application
                ? application.errorCode().name():StandardErrorCode.INTERNAL_ERROR.name();
        try {
            audits.appendIndependent(new SecurityAuditDraft(actor.companyId(),
                    action.toLowerCase(Locale.ROOT)+":"+targetId+":"+commandId,action,
                    SecurityAuditOutcome.FAILED,
                    SecurityAuditActor.user(actor.userId(),actor.platformRoles().stream()
                            .map(Enum::name).collect(Collectors.toUnmodifiableSet())),
                    targetType,targetId,normalized(reason),null,null,errorCode,null,
                    clientType,clientVersion,clock.instant()));
        } catch(RuntimeException auditFailure) {
            throw new ApplicationException(StandardErrorCode.INTERNAL_ERROR);
        }
    }

    private IdempotencyExecutionResult execute(CurrentActor actor, String method, String operation, UUID key,
                                               RequestHash hash, java.util.function.Supplier<StoredCommandResult> action) {
        return idempotency.execute(new IdempotencyCommand(
                new IdempotencyScope(actor.userId(), method, operation, key), hash), action);
    }

    private ProjectAccessSnapshot requireOwnerOrAdmin(CurrentActor actor, UUID projectId, boolean write) {
        ProjectAccessSnapshot access = query.requireVisible(actor, projectId);
        if (access.actorAccess() == ProjectAccessSnapshot.ActorProjectAccess.OWNER
                || actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) return access;
        throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
    }

    private void requireAvailableUser(UUID companyId, UUID userId, String field) {
        MinimalUserSnapshot user = users.findByUserId(companyId, userId).orElse(null);
        if (user == null || !user.activeAndEnabled()) {
            throw ApplicationException.validation(new FieldViolation(
                    field, "INVALID_MEMBER", "成员必须是本企业在职且启用的用户"));
        }
    }

    private static void requireReasonForAdminPath(CurrentActor actor, ProjectAccessSnapshot access,
                                                  String reason) {
        if (access.actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.OWNER
                && actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            requireReason(reason);
            return;
        }
        if (reason != null && reason.strip().length() > 500) {
            throw ApplicationException.validation(new FieldViolation(
                    "reason", "INVALID_LENGTH", "理由长度不能超过 500 字符"));
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.strip().length() < 10 || reason.strip().length() > 500) {
            throw ApplicationException.validation(new FieldViolation(
                    "reason", "INVALID_LENGTH", "治理理由长度必须在 10 到 500 字符之间"));
        }
    }

    private void appendMemberEvent(String type, ProjectMemberSnapshot member, CurrentActor actor,
                                   String source) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("projectId", member.projectId()); payload.put("membershipId", member.membershipId());
        payload.put("userId", member.userId()); payload.put("membershipStatus", member.membershipStatus());
        payload.put("changeSource", source);
        events.append(new EventDraft(type, 1, "ProjectMembership", member.membershipId(),
                member.rowVersion(), actor.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
    }

    private void appendAudit(String action, UUID targetId, CurrentActor actor, String reason,
                             UUID commandId, String clientType, String clientVersion,
                             Object before, Object after) {
        audits.append(new SecurityAuditDraft(actor.companyId(),
                action.toLowerCase(Locale.ROOT) + ":" + targetId + ":" + commandId,
                action, SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(actor.userId(), actor.platformRoles().stream()
                        .map(Enum::name).collect(Collectors.toUnmodifiableSet())),
                action.contains("OWNER") ? "PROJECT" : "PROJECT_MEMBERSHIP", targetId.toString(),
                normalized(reason), before == null ? null : objectMapper.valueToTree(before),
                objectMapper.valueToTree(after), null, commandId, clientType, clientVersion, clock.instant()));
    }

    private StoredCommandResult stored(int status, Object body, String etag) {
        try { return new StoredCommandResult(status, objectMapper.writeValueAsString(responseBody(body)),
                resourceId(body), etag); }
        catch (JacksonException exception) { throw new IllegalStateException("project response serialization failed", exception); }
    }

    private static UUID resourceId(Object body) {
        if (body instanceof ProjectMemberSnapshot member) return member.membershipId();
        if (body instanceof ProjectSnapshot project) return project.projectId();
        throw new IllegalArgumentException("unsupported response body");
    }

    private static Object responseBody(Object body) {
        if (!(body instanceof ProjectSnapshot project)) return body;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",project.projectId()); result.put("workspaceId",project.workspaceId());
        result.put("code",project.code()); result.put("name",project.name());
        result.put("description",project.description()); result.put("projectType",project.projectType());
        result.put("lifecycle",project.lifecycle()); result.put("ownerUserId",project.ownerUserId());
        result.put("templateKey",project.templateKey()); result.put("templateVersion",project.templateVersion());
        result.put("customerName",project.customerName()); result.put("customerReference",project.customerReference());
        result.put("deliverySite",project.deliverySite()); result.put("contactNote",project.contactNote());
        result.put("rowVersion",project.rowVersion());
        return result;
    }

    private static Map<String,Object> memberAudit(ProjectMemberSnapshot member) {
        return Map.of("projectId",member.projectId(),"userId",member.userId(),
                "membershipStatus",member.membershipStatus(),"rowVersion",member.rowVersion());
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }

    private static void requireCompanyAdmin(CurrentActor actor) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN))
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
    }
}
