package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusChangeCommand;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusCommandActor;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusUseCase;
import com.yumpoo.platform.identityaccess.application.audit.IdentitySecurityAuditRecorder;
import com.yumpoo.platform.identityaccess.application.authorization.GovernanceMemberState;
import com.yumpoo.platform.identityaccess.application.authorization.GovernanceStateQueryService;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleAssignmentQueryUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentPage;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentQuery;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentSnapshot;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentStatus;
import com.yumpoo.platform.identityaccess.application.session.AuthenticatedSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApiV1Controller
public final class IdentityGovernanceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdentityGovernanceController.class);

    private final CurrentActorProvider currentActorProvider;
    private final GovernanceStateQueryService stateQuery;
    private final PlatformRoleAssignmentQueryUseCase roleQuery;
    private final PlatformRoleCommandPort roleCommands;
    private final AccountStatusUseCase accountStatusUseCase;
    private final IfMatchParser ifMatchParser;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final IdempotencyRequestHasher requestHasher;
    private final IdentitySecurityAuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    public IdentityGovernanceController(
            CurrentActorProvider currentActorProvider,
            GovernanceStateQueryService stateQuery,
            PlatformRoleAssignmentQueryUseCase roleQuery,
            PlatformRoleCommandPort roleCommands,
            AccountStatusUseCase accountStatusUseCase,
            IfMatchParser ifMatchParser,
            IdempotencyKeyParser idempotencyKeyParser,
            IdempotencyRequestHasher requestHasher,
            IdentitySecurityAuditRecorder auditRecorder,
            ObjectMapper objectMapper
    ) {
        this.currentActorProvider = currentActorProvider;
        this.stateQuery = stateQuery;
        this.roleQuery = roleQuery;
        this.roleCommands = roleCommands;
        this.accountStatusUseCase = accountStatusUseCase;
        this.ifMatchParser = ifMatchParser;
        this.idempotencyKeyParser = idempotencyKeyParser;
        this.requestHasher = requestHasher;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/admin/members/{userId}/governance-state")
    ResponseEntity<GovernanceStateResponse> governanceState(@PathVariable UUID userId) {
        CurrentActor actor = currentActorProvider.requiredActive();
        GovernanceMemberState state = stateQuery.findMember(actor.companyId(), actor.userId(), userId);
        return ResponseEntity.ok()
                .eTag(Long.toString(state.rowVersion()))
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(GovernanceStateResponse.from(state));
    }

    @GetMapping("/admin/role-assignments")
    RoleAssignmentPageResponse roleAssignments(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) ManagedPlatformRole role,
            @RequestParam(required = false) RoleAssignmentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        RoleAssignmentPage result = roleQuery.find(new RoleAssignmentQuery(
                actor.companyId(), actor.userId(), userId, role, status, page, size));
        return RoleAssignmentPageResponse.from(result);
    }

    @PostMapping("/admin/company-admin-assignments")
    ResponseEntity<String> grantCompanyAdmin(
            @Valid @RequestBody RoleGrantRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        return grant(PlatformRoleCode.COMPANY_ADMIN, body, ifMatch, idempotencyKey, request);
    }

    @PostMapping("/admin/app-manager-assignments")
    ResponseEntity<String> grantAppManager(
            @Valid @RequestBody RoleGrantRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        return grant(PlatformRoleCode.APP_MANAGER, body, ifMatch, idempotencyKey, request);
    }

    @DeleteMapping("/admin/company-admin-assignments/{assignmentId}")
    ResponseEntity<String> revokeCompanyAdmin(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody GovernanceReasonRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        return revoke(PlatformRoleCode.COMPANY_ADMIN, assignmentId, body,
                ifMatch, idempotencyKey, request);
    }

    @DeleteMapping("/admin/app-manager-assignments/{assignmentId}")
    ResponseEntity<String> revokeAppManager(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody GovernanceReasonRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        return revoke(PlatformRoleCode.APP_MANAGER, assignmentId, body,
                ifMatch, idempotencyKey, request);
    }

    @PostMapping("/admin/members/{userId}/account-disable")
    ResponseEntity<String> disableAccount(
            @PathVariable UUID userId,
            @Valid @RequestBody GovernanceReasonRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        return changeAccount(userId, true, body, ifMatch, idempotencyKey, request);
    }

    @PostMapping("/admin/members/{userId}/account-enable")
    ResponseEntity<String> enableAccount(
            @PathVariable UUID userId,
            @Valid @RequestBody GovernanceReasonRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        return changeAccount(userId, false, body, ifMatch, idempotencyKey, request);
    }

    private ResponseEntity<String> grant(
            PlatformRoleCode role,
            RoleGrantRequest body,
            String ifMatch,
            String idempotencyHeader,
        HttpServletRequest request
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(idempotencyHeader);
        String operation = role == PlatformRoleCode.APP_MANAGER
                ? "grantAppManager" : "grantCompanyAdmin";
        try {
            GovernanceMemberState visible = stateQuery.findMember(
                    actor.companyId(), actor.userId(), body.userId());
            long expectedVersion = ifMatchParser.parseForVisibleResource(true, ifMatch);
            PlatformRoleCommandReceipt result = roleCommands.grant(new PlatformRoleGrantCommand(
                    actor.companyId(), visible.userId(), role, expectedVersion,
                    roleActor(actor, request), idempotencyKey,
                    requestHasher.hash(operation,
                            Map.of("userId", visible.userId().toString(),
                                    "ifMatch", Long.toString(expectedVersion)),
                            objectMapper.valueToTree(body)).value(),
                    body.reason()));
            String base = role == PlatformRoleCode.APP_MANAGER
                    ? "/api/v1/admin/app-manager-assignments/"
                    : "/api/v1/admin/company-admin-assignments/";
            return roleResponse(result, HttpStatus.CREATED,
                    URI.create(base + result.mutation().assignmentId()));
        } catch (RuntimeException exception) {
            failClosed(actor, idempotencyKey, operation, "USER", body.userId(),
                    body.reason(), request, exception);
            throw exception;
        }
    }

    private ResponseEntity<String> revoke(
            PlatformRoleCode role,
            UUID assignmentId,
            GovernanceReasonRequest body,
            String ifMatch,
            String idempotencyHeader,
        HttpServletRequest request
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(idempotencyHeader);
        String operation = role == PlatformRoleCode.APP_MANAGER
                ? "revokeAppManager" : "revokeCompanyAdmin";
        try {
            ManagedPlatformRole managedRole = ManagedPlatformRole.valueOf(role.name());
            RoleAssignmentSnapshot visible = stateQuery.findAssignment(
                    actor.companyId(), actor.userId(), assignmentId, managedRole);
            long expectedVersion = ifMatchParser.parseForVisibleResource(true, ifMatch);
            PlatformRoleCommandReceipt result = roleCommands.revoke(new PlatformRoleRevokeCommand(
                    actor.companyId(), visible.assignmentId(), role, expectedVersion,
                    roleActor(actor, request), idempotencyKey,
                    requestHasher.hash(operation,
                            Map.of("assignmentId", assignmentId.toString(),
                                    "ifMatch", Long.toString(expectedVersion)),
                            objectMapper.valueToTree(body)).value(),
                    body.reason()));
            return roleResponse(result, HttpStatus.OK, null);
        } catch (RuntimeException exception) {
            failClosed(actor, idempotencyKey, operation, "PLATFORM_ROLE_ASSIGNMENT",
                    assignmentId, body.reason(), request, exception);
            throw exception;
        }
    }

    private ResponseEntity<String> changeAccount(
            UUID userId,
            boolean disable,
            GovernanceReasonRequest body,
            String ifMatch,
            String idempotencyHeader,
        HttpServletRequest request
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(idempotencyHeader);
        String operation = disable
                ? "disableMemberAccount" : "enableMemberAccount";
        try {
            GovernanceMemberState visible = stateQuery.findMember(
                    actor.companyId(), actor.userId(), userId);
            long expectedVersion = ifMatchParser.parseForVisibleResource(true, ifMatch);
            var commandActor = accountActor(actor, request);
            var requestHash = requestHasher.hash(operation,
                    Map.of("userId", userId.toString(),
                            "ifMatch", Long.toString(expectedVersion)),
                    objectMapper.valueToTree(body));
            AccountStatusChangeCommand command = disable
                    ? AccountStatusChangeCommand.disable(
                            actor.companyId(), visible.userId(), commandActor, expectedVersion,
                            idempotencyKey, requestHash, body.reason())
                    : AccountStatusChangeCommand.enable(
                            actor.companyId(), visible.userId(), commandActor, expectedVersion,
                            idempotencyKey, requestHash, body.reason());
            IdempotencyExecutionResult result = accountStatusUseCase.change(
                    command);
            return stored(result.result(), null);
        } catch (RuntimeException exception) {
            failClosed(actor, idempotencyKey, operation, "USER", userId,
                    body.reason(), request, exception);
            throw exception;
        }
    }

    private void failClosed(
            CurrentActor actor,
            UUID idempotencyKey,
            String action,
            String targetType,
            Object targetId,
            String reason,
            HttpServletRequest request,
            RuntimeException original
    ) {
        String errorCode = original instanceof ApplicationException applicationException
                ? applicationException.errorCode().name() : StandardErrorCode.INTERNAL_ERROR.name();
        try {
            auditRecorder.failedIndependent(
                    actor.companyId(), "failed:" + action + ":" + idempotencyKey + ":" + errorCode,
                    actionToAudit(action), SecurityAuditActor.user(actor.userId(), roleNames(actor)),
                    targetType, targetId, safeReason(reason), errorCode,
                    SessionRequestContext.required(request).clientTypeCode(),
                    SessionRequestContext.required(request).clientVersion());
        } catch (RuntimeException auditFailure) {
            LOGGER.error(
                    "security audit append failed requestId={} action={} errorType={}",
                    request.getAttribute(com.yumpoo.platform.foundation.application.request.RequestIdContext.ATTRIBUTE_NAME),
                    action, auditFailure.getClass().getSimpleName());
            throw new ApplicationException(StandardErrorCode.INTERNAL_ERROR);
        }
    }

    private static String actionToAudit(String operation) {
        return switch (operation) {
            case "grantAppManager", "grantCompanyAdmin" -> "PLATFORM_ROLE_GRANT_FAILED";
            case "revokeAppManager", "revokeCompanyAdmin" -> "PLATFORM_ROLE_REVOKE_FAILED";
            case "disableMemberAccount" -> "ACCOUNT_DISABLE_FAILED";
            case "enableMemberAccount" -> "ACCOUNT_ENABLE_FAILED";
            default -> "IDENTITY_GOVERNANCE_FAILED";
        };
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 160) {
            return null;
        }
        return reason.strip();
    }

    private static PlatformRoleCommandActor roleActor(
            CurrentActor actor,
            HttpServletRequest request
    ) {
        AuthenticatedSession session = SessionRequestContext.required(request);
        return new PlatformRoleCommandActor(
                actor.userId(), actor.authorizationVersion(), session.authenticatedAt());
    }

    private static AccountStatusCommandActor accountActor(CurrentActor actor, HttpServletRequest request) {
        AuthenticatedSession session = SessionRequestContext.required(request);
        return new AccountStatusCommandActor(
                actor.userId(), actor.authorizationVersion(), session.authenticatedAt());
    }

    private static Set<String> roleNames(CurrentActor actor) {
        return actor.platformRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    private static ResponseEntity<String> stored(StoredCommandResult stored, URI location) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (stored.etag() != null) {
            headers.setETag(stored.etag());
        }
        if (location != null) {
            headers.setLocation(location);
        }
        return new ResponseEntity<>(stored.responseJson(), headers, HttpStatus.valueOf(stored.httpStatus()));
    }

    private ResponseEntity<String> roleResponse(
            PlatformRoleCommandReceipt receipt,
            HttpStatus status,
            URI location
    ) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setETag('"' + Long.toString(receipt.mutation().assignmentRowVersion()) + '"');
            if (location != null) {
                headers.setLocation(location);
            }
            return new ResponseEntity<>(
                    objectMapper.writeValueAsString(receipt.mutation()),
                    headers,
                    status
            );
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("platform role response serialization failed", exception);
        }
    }
}
