package com.yumpoo.platform.identityaccess.application.authorization;

import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.application.session.SessionRevocationService;
import com.yumpoo.platform.identityaccess.application.audit.IdentitySecurityAuditRecorder;
import com.yumpoo.platform.identityaccess.application.session.SessionRevocationTarget;
import com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlatformRoleManagementService
        implements PlatformRoleManagementUseCase, PlatformRoleMaintenanceUseCase {

    static final Duration RECENT_AUTHENTICATION_WINDOW = Duration.ofMinutes(15);
    public static final String ROLE_GRANTED_EVENT = "identity.platform_role_granted";
    public static final String ROLE_REVOKED_EVENT = "identity.platform_role_revoked";
    private static final String GRANT_ROUTE_KEY = "platformRoleGrant";
    private static final String REVOKE_ROUTE_KEY = "platformRoleRevoke";

    private final RoleGovernanceRepository repository;
    private final AppManagerAvailabilityCoordinator availabilityCoordinator;
    private final SessionRevocationService sessionRevocationService;
    private final TransactionalEventPort eventPort;
    private final IdempotentCommandExecutor idempotentCommandExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IdentitySecurityAuditRecorder auditRecorder;

    public PlatformRoleManagementService(
            RoleGovernanceRepository repository,
            AppManagerAvailabilityCoordinator availabilityCoordinator,
            SessionRevocationService sessionRevocationService,
            TransactionalEventPort eventPort,
            IdempotentCommandExecutor idempotentCommandExecutor,
            ObjectMapper objectMapper,
            Clock clock,
            IdentitySecurityAuditRecorder auditRecorder
    ) {
        this.repository = repository;
        this.availabilityCoordinator = availabilityCoordinator;
        this.sessionRevocationService = sessionRevocationService;
        this.eventPort = eventPort;
        this.idempotentCommandExecutor = idempotentCommandExecutor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.auditRecorder = auditRecorder;
    }

    @Override
    public IdempotencyExecutionResult grant(GrantPlatformRoleCommand command) {
        IdempotencyCommand idempotency = new IdempotencyCommand(
                new IdempotencyScope(
                        command.actor().userId(), "POST", grantRouteKey(command.role()),
                        command.idempotencyKey()),
                command.requestHash()
        );
        return idempotentCommandExecutor.execute(idempotency, () -> {
            PlatformRoleMutationResult result = executeGrant(command);
            return stored(result, 201);
        });
    }

    @Override
    public IdempotencyExecutionResult revoke(RevokePlatformRoleCommand command) {
        IdempotencyCommand idempotency = new IdempotencyCommand(
                new IdempotencyScope(
                        command.actor().userId(), "DELETE", revokeRouteKey(command.expectedRole()),
                        command.idempotencyKey()),
                command.requestHash()
        );
        return idempotentCommandExecutor.execute(idempotency, () -> {
            PlatformRoleMutationResult result = executeRevoke(command);
            return stored(result, 200);
        });
    }

    @Override
    @Transactional
    public PlatformRoleMutationResult execute(MaintenanceRoleCommand command) {
        AvailabilitySnapshot before = availabilityCoordinator.lock(command.companyId());
        RoleUserSnapshot target = requireTarget(command.companyId(), command.targetUserId());
        if (!target.available()) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "维护目标必须是在职且启用的用户");
        }
        if (repository.findActiveAssignment(command.companyId(), command.targetUserId(),
                ManagedPlatformRole.APP_MANAGER).isPresent()) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "目标已经拥有 APP_MANAGER");
        }
        switch (command.mode()) {
            case BOOTSTRAP -> {
                if (before.state().lifecycleStatus() != GovernanceLifecycleStatus.UNINITIALIZED
                        || repository.hasAppManagerHistory(command.companyId())) {
                    throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION,
                            "首管引导已永久关闭");
                }
            }
            case BREAK_GLASS -> {
                if (before.state().lifecycleStatus() == GovernanceLifecycleStatus.UNINITIALIZED
                        || before.availableCount() != 0) {
                    throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION,
                            "仅在已初始化且没有可用 APP_MANAGER 时允许紧急恢复");
                }
            }
        }

        Instant now = clock.instant();
        EventActor actor = EventActor.system(command.mode().systemCode());
        PlatformRoleMutationResult result = grantLocked(
                command.companyId(), target, ManagedPlatformRole.APP_MANAGER,
                "SYSTEM", null, command.mode().systemCode(), command.reasonReference(), actor, now);
        if (command.mode() == MaintenanceRoleMode.BOOTSTRAP) {
            availabilityCoordinator.initializeAvailable(command.companyId());
        } else {
            availabilityCoordinator.restoreAvailable(
                    before, "BREAK_GLASS", command.targetUserId(), actor);
        }
        auditRecorder.succeeded(
                command.companyId(), "role:" + result.assignmentId() + ":" + result.assignmentRowVersion(),
                command.mode() == MaintenanceRoleMode.BOOTSTRAP
                        ? "APP_MANAGER_BOOTSTRAPPED" : "APP_MANAGER_BREAK_GLASS_GRANTED",
                actor, Set.of(), "PLATFORM_ROLE_ASSIGNMENT", result.assignmentId(),
                command.reasonReference(), null,
                Map.of("userId", result.userId(), "role", result.role().name(),
                        "status", result.status().name(),
                        "authorizationVersion", result.authorizationVersion()),
                result.assignmentId(), null, null);
        return result;
    }

    private PlatformRoleMutationResult executeGrant(GrantPlatformRoleCommand command) {
        AvailabilitySnapshot before = availabilityCoordinator.lock(command.companyId());
        RoleUserSnapshot actorUser = requireAuthorizedActor(command.companyId(), command.actor());
        RoleUserSnapshot target = requireTarget(command.companyId(), command.targetUserId());
        if (target.rowVersion() != command.expectedTargetRowVersion()) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        if (!target.available()) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "只能向在职且启用的用户授予角色");
        }
        if (repository.findActiveAssignment(command.companyId(), command.targetUserId(),
                command.role()).isPresent()) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "目标已经拥有该角色");
        }
        EventActor actor = EventActor.adminOverride(actorUser.userId(), command.reasonReference());
        PlatformRoleMutationResult result = grantLocked(
                command.companyId(), target, command.role(), "USER", actorUser.userId(), null,
                command.reasonReference(), actor, clock.instant());
        auditRecorder.succeeded(
                command.companyId(), "role:" + result.assignmentId() + ":" + result.assignmentRowVersion(),
                "PLATFORM_ROLE_GRANTED", actor, roleNames(actorUser),
                "PLATFORM_ROLE_ASSIGNMENT", result.assignmentId(), command.reasonReference(),
                null, Map.of("userId", result.userId(), "role", result.role().name(),
                        "status", result.status().name(),
                        "authorizationVersion", result.authorizationVersion()),
                command.idempotencyKey(), null, null);
        availabilityCoordinator.reconcile(before, "ROLE_GRANTED", target.userId(), actor);
        return result;
    }

    private PlatformRoleMutationResult executeRevoke(RevokePlatformRoleCommand command) {
        AvailabilitySnapshot before = availabilityCoordinator.lock(command.companyId());
        RoleUserSnapshot actorUser = requireAuthorizedActor(command.companyId(), command.actor());
        RoleAssignmentSnapshot assignment = repository.lockAssignment(
                        command.companyId(), command.assignmentId(), command.expectedRole())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (assignment.rowVersion() != command.expectedAssignmentRowVersion()) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        if (assignment.status() != RoleAssignmentStatus.ACTIVE) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        RoleUserSnapshot target = requireTarget(command.companyId(), assignment.userId());
        boolean removesAvailableManager = assignment.role() == ManagedPlatformRole.APP_MANAGER
                && target.available();
        availabilityCoordinator.protectLastAvailable(before, removesAvailableManager);

        Instant now = clock.instant();
        RoleAssignmentSnapshot revoked = repository.revoke(
                assignment, actorUser.userId(), command.reasonReference(), now);
        RoleUserSnapshot changedUser = repository.incrementAuthorizationVersion(
                command.companyId(), target.userId(), target.rowVersion());
        EventActor actor = EventActor.adminOverride(actorUser.userId(), command.reasonReference());
        PlatformRoleMutationResult result = result(revoked, changedUser, now);
        publishRoleEvent(ROLE_REVOKED_EVENT, result, command.reasonReference(), actor);
        revokeSessions(changedUser, actor);
        auditRecorder.succeeded(
                command.companyId(), "role:" + result.assignmentId() + ":" + result.assignmentRowVersion(),
                "PLATFORM_ROLE_REVOKED", actor, roleNames(actorUser),
                "PLATFORM_ROLE_ASSIGNMENT", result.assignmentId(), command.reasonReference(),
                Map.of("userId", result.userId(), "role", result.role().name(), "status", "ACTIVE"),
                Map.of("userId", result.userId(), "role", result.role().name(),
                        "status", result.status().name(),
                        "authorizationVersion", result.authorizationVersion()),
                command.idempotencyKey(), null, null);
        availabilityCoordinator.reconcile(before, "ROLE_REVOKED", target.userId(), actor);
        return result;
    }

    private PlatformRoleMutationResult grantLocked(
            UUID companyId,
            RoleUserSnapshot target,
            ManagedPlatformRole role,
            String actorType,
            UUID actorUserId,
            String systemCode,
            String reasonReference,
            EventActor actor,
            Instant now
    ) {
        RoleAssignmentSnapshot assignment = repository.grant(
                UUID.randomUUID(), companyId, target.userId(), role,
                actorType, actorUserId, systemCode, reasonReference, now);
        RoleUserSnapshot changedUser = repository.incrementAuthorizationVersion(
                companyId, target.userId(), target.rowVersion());
        PlatformRoleMutationResult result = result(assignment, changedUser, now);
        publishRoleEvent(ROLE_GRANTED_EVENT, result, reasonReference, actor);
        revokeSessions(changedUser, actor);
        return result;
    }

    private RoleUserSnapshot requireAuthorizedActor(UUID companyId, RoleCommandActor actor) {
        Instant now = clock.instant();
        if (actor.authenticatedAt().isBefore(now.minus(RECENT_AUTHENTICATION_WINDOW))
                || actor.authenticatedAt().isAfter(now)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED,
                    "最近认证已过期，请重新登录后再试");
        }
        RoleUserSnapshot user = repository.lockUser(companyId, actor.userId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.ACCESS_DENIED));
        if (!user.available()
                || user.authorizationVersion() != actor.sessionAuthorizationVersion()
                || !user.activeRoles().contains(ManagedPlatformRole.APP_MANAGER)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        return user;
    }

    private RoleUserSnapshot requireTarget(UUID companyId, UUID targetUserId) {
        return repository.lockUser(companyId, targetUserId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private void revokeSessions(RoleUserSnapshot user, EventActor actor) {
        sessionRevocationService.revokeActive(
                new SessionRevocationTarget(
                        user.userId(), user.companyId(),
                        user.authorizationVersion(), user.rowVersion()),
                SessionRevocationReason.AUTHORIZATION_CHANGED,
                actor
        );
    }

    private void publishRoleEvent(
            String eventType,
            PlatformRoleMutationResult result,
            String reasonReference,
            EventActor actor
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignmentId", result.assignmentId());
        payload.put("userId", result.userId());
        payload.put("roleCode", result.role().name());
        payload.put("scopeType", result.role().scopeType());
        payload.put("scopeId", result.companyId());
        payload.put("reasonReference", reasonReference);
        payload.put("authorizationVersion", result.authorizationVersion());
        eventPort.append(new EventDraft(
                eventType, 1, "PlatformRoleAssignment", result.assignmentId(),
                result.assignmentRowVersion(),
                result.companyId(), actor,
                objectMapper.valueToTree(payload)
        ));
    }

    private PlatformRoleMutationResult result(
            RoleAssignmentSnapshot assignment,
            RoleUserSnapshot user,
            Instant changedAt
    ) {
        return new PlatformRoleMutationResult(
                assignment.assignmentId(), assignment.companyId(), assignment.userId(),
                assignment.role(), assignment.status(),
                assignment.rowVersion(), user.rowVersion(), user.authorizationVersion(), changedAt);
    }

    private StoredCommandResult stored(PlatformRoleMutationResult result, int status) {
        try {
            return new StoredCommandResult(
                    status,
                    objectMapper.writeValueAsString(result),
                    result.assignmentId(),
                    StrongEtag.format(result.assignmentRowVersion())
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("platform role result serialization failed", exception);
        }
    }

    private static String grantRouteKey(ManagedPlatformRole role) {
        return GRANT_ROUTE_KEY + role.name();
    }

    private static String revokeRouteKey(ManagedPlatformRole role) {
        return REVOKE_ROUTE_KEY + role.name();
    }

    private static Set<String> roleNames(RoleUserSnapshot user) {
        return user.activeRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
