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
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class AccountStatusService implements AccountStatusUseCase {

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

    public AccountStatusService(
            AccountStatusRepository repository,
            RoleGovernanceRepository roleGovernanceRepository,
            AppManagerAvailabilityCoordinator availabilityCoordinator,
            SessionRevocationService sessionRevocationService,
            TransactionalEventPort eventPort,
            IdempotentCommandExecutor idempotentCommandExecutor,
            ObjectMapper objectMapper
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
    }

    @Override
    public IdempotencyExecutionResult change(AccountStatusChangeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String routeKey = command.desiredStatus() == AccountStatus.DISABLED
                ? DISABLE_ROUTE_KEY
                : ENABLE_ROUTE_KEY;
        IdempotencyCommand idempotencyCommand = new IdempotencyCommand(
                new IdempotencyScope(
                        command.actorUserId(),
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
        EventActor actor = EventActor.adminOverride(command.actorUserId(), command.reason());
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
}
