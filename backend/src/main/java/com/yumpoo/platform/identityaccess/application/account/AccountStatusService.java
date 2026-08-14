package com.yumpoo.platform.identityaccess.application.account;

import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.application.session.SessionRevocationService;
import com.yumpoo.platform.identityaccess.application.session.SessionRevocationTarget;
import com.yumpoo.platform.identityaccess.application.authorization.AppManagerAvailabilityCoordinator;
import com.yumpoo.platform.identityaccess.application.authorization.AvailabilitySnapshot;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.RoleGovernanceRepository;
import com.yumpoo.platform.identityaccess.application.authorization.RoleUserSnapshot;
import com.yumpoo.platform.identityaccess.application.audit.IdentitySecurityAuditRecorder;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

@Service
public class AccountStatusService implements AccountStatusUseCase {

    static final Duration RECENT_AUTHENTICATION_WINDOW = Duration.ofMinutes(15);

    public static final String DISABLE_ROUTE_KEY = "adminMemberAccountDisable";
    public static final String ENABLE_ROUTE_KEY = "adminMemberAccountEnable";
    public static final String USER_ACCOUNT_DISABLED = "identity.user_account_disabled";
    public static final String USER_ACCOUNT_ENABLED = "identity.user_account_enabled";

    private final AccountStatusRepository repository;
    private final RoleGovernanceRepository roleGovernanceRepository;
    private final AppManagerAvailabilityCoordinator availabilityCoordinator;
    private final SessionRevocationService sessionRevocationService;
    private final TransactionalEventPort eventPort;
    private final IdempotentCommandExecutor idempotentCommandExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IdentitySecurityAuditRecorder auditRecorder;

    public AccountStatusService(
            AccountStatusRepository repository,
            RoleGovernanceRepository roleGovernanceRepository,
            AppManagerAvailabilityCoordinator availabilityCoordinator,
            SessionRevocationService sessionRevocationService,
            TransactionalEventPort eventPort,
            IdempotentCommandExecutor idempotentCommandExecutor,
            ObjectMapper objectMapper,
            Clock clock,
            IdentitySecurityAuditRecorder auditRecorder
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.roleGovernanceRepository = Objects.requireNonNull(
                roleGovernanceRepository, "roleGovernanceRepository must not be null");
        this.availabilityCoordinator = Objects.requireNonNull(
                availabilityCoordinator, "availabilityCoordinator must not be null");
        this.sessionRevocationService = Objects.requireNonNull(
                sessionRevocationService,
                "sessionRevocationService must not be null"
        );
        this.eventPort = Objects.requireNonNull(eventPort, "eventPort must not be null");
        this.idempotentCommandExecutor = Objects.requireNonNull(
                idempotentCommandExecutor,
                "idempotentCommandExecutor must not be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder must not be null");
    }

    @Override
    public IdempotencyExecutionResult change(AccountStatusChangeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String routeKey = command.desiredStatus() == AccountStatus.DISABLED
                ? DISABLE_ROUTE_KEY
                : ENABLE_ROUTE_KEY;
        IdempotencyCommand idempotencyCommand = new IdempotencyCommand(
                new IdempotencyScope(
                        command.actor().userId(),
                        "POST",
                        routeKey,
                        command.idempotencyKey()
                ),
                command.requestHash()
        );
        return idempotentCommandExecutor.execute(
                idempotencyCommand,
                () -> executeChange(command)
        );
    }

    private StoredCommandResult executeChange(AccountStatusChangeCommand command) {
        AvailabilitySnapshot before = availabilityCoordinator.lock(command.companyId());
        RoleUserSnapshot actorUser = requireAuthorizedActor(command);
        RoleUserSnapshot targetBefore = roleGovernanceRepository
                .lockUser(command.companyId(), command.targetUserId())
                .orElseThrow(() -> new com.yumpoo.platform.foundation.application.error.ApplicationException(
                        com.yumpoo.platform.foundation.application.error.StandardErrorCode.RESOURCE_NOT_FOUND));
        availabilityCoordinator.protectLastAvailable(
                before,
                command.desiredStatus() == AccountStatus.DISABLED
                        && targetBefore.available()
                        && targetBefore.activeRoles().contains(ManagedPlatformRole.APP_MANAGER)
        );
        AccountStatusSnapshot changed = repository.change(command);
        EventActor actor = EventActor.adminOverride(command.actor().userId(), command.reason());
        publishAccountStatusChanged(changed, actor);
        sessionRevocationService.revokeActive(
                new SessionRevocationTarget(
                        changed.userId(),
                        changed.companyId(),
                        changed.authorizationVersion(),
                        changed.rowVersion()
                ),
                command.desiredStatus() == AccountStatus.DISABLED
                        ? SessionRevocationReason.ACCOUNT_DISABLED
                        : SessionRevocationReason.AUTHORIZATION_CHANGED,
                actor
        );
        availabilityCoordinator.reconcile(
                before,
                command.desiredStatus() == AccountStatus.DISABLED
                        ? "ACCOUNT_DISABLED" : "ACCOUNT_ENABLED",
                command.targetUserId(),
                actor
        );
        auditRecorder.succeeded(
                command.companyId(),
                "account-status:" + changed.userId() + ":" + changed.rowVersion(),
                changed.accountStatus() == AccountStatus.DISABLED
                        ? "ACCOUNT_DISABLED" : "ACCOUNT_ENABLED",
                actor,
                actorUser.activeRoles().stream().map(Enum::name)
                        .collect(Collectors.toUnmodifiableSet()),
                "USER", changed.userId(), command.reason(),
                Map.of("accountStatus", changed.accountStatus() == AccountStatus.DISABLED
                        ? "ENABLED" : "DISABLED"),
                Map.of("employmentStatus", changed.employmentStatus().name(),
                        "accountStatus", changed.accountStatus().name(),
                        "authorizationVersion", changed.authorizationVersion()),
                command.idempotencyKey(), null, null);

        AccountStatusChangeResult result = new AccountStatusChangeResult(
                changed.userId(),
                changed.employmentStatus(),
                changed.accountStatus(),
                changed.authorizationVersion(),
                changed.rowVersion()
        );
        return new StoredCommandResult(
                200,
                writeJson(result),
                changed.userId(),
                StrongEtag.format(changed.rowVersion())
        );
    }

    private void publishAccountStatusChanged(AccountStatusSnapshot changed, EventActor actor) {
        boolean disabled = changed.accountStatus() == AccountStatus.DISABLED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", changed.userId());
        payload.put("fromStatus", disabled ? "ENABLED" : "DISABLED");
        payload.put("toStatus", changed.accountStatus().name());
        payload.put("reasonCode", disabled
                ? "ADMIN_ACCOUNT_DISABLED"
                : "ADMIN_ACCOUNT_ENABLED");
        payload.put("authorizationVersion", changed.authorizationVersion());
        eventPort.append(new EventDraft(
                disabled ? USER_ACCOUNT_DISABLED : USER_ACCOUNT_ENABLED,
                1,
                "User",
                changed.userId(),
                changed.rowVersion(),
                changed.companyId(),
                actor,
                objectMapper.valueToTree(payload)
        ));
    }

    private String writeJson(AccountStatusChangeResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new IllegalStateException("account status result serialization failed", exception);
        }
    }

    private RoleUserSnapshot requireAuthorizedActor(AccountStatusChangeCommand command) {
        Instant now = clock.instant();
        AccountStatusCommandActor actor = command.actor();
        if (actor.authenticatedAt().isBefore(now.minus(RECENT_AUTHENTICATION_WINDOW))
                || actor.authenticatedAt().isAfter(now)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED,
                    "最近认证已过期，请重新登录后再试");
        }
        RoleUserSnapshot user = roleGovernanceRepository
                .lockUser(command.companyId(), actor.userId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.ACCESS_DENIED));
        if (!user.available()
                || user.authorizationVersion() != actor.sessionAuthorizationVersion()
                || !user.activeRoles().contains(ManagedPlatformRole.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        return user;
    }
}
