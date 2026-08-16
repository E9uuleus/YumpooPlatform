package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.application.authorization.GrantPlatformRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleManagementUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMutationResult;
import com.yumpoo.platform.identityaccess.application.authorization.RevokePlatformRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.RoleCommandActor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

@Component
public class PlatformRoleCommandAdapter implements PlatformRoleCommandPort {

    private final PlatformRoleManagementUseCase useCase;
    private final ObjectMapper objectMapper;

    public PlatformRoleCommandAdapter(
            PlatformRoleManagementUseCase useCase,
            ObjectMapper objectMapper
    ) {
        this.useCase = Objects.requireNonNull(useCase, "useCase must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public PlatformRoleCommandReceipt grant(PlatformRoleGrantCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return receipt(useCase.grant(new GrantPlatformRoleCommand(
                command.companyId(),
                command.targetUserId(),
                ManagedPlatformRole.valueOf(command.role().name()),
                command.expectedTargetRowVersion(),
                actor(command.actor()),
                command.idempotencyKey(),
                new RequestHash(command.requestHash()),
                command.reasonReference()
        )));
    }

    @Override
    public PlatformRoleCommandReceipt revoke(PlatformRoleRevokeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return receipt(useCase.revoke(new RevokePlatformRoleCommand(
                command.companyId(),
                command.assignmentId(),
                ManagedPlatformRole.valueOf(command.expectedRole().name()),
                command.expectedAssignmentRowVersion(),
                actor(command.actor()),
                command.idempotencyKey(),
                new RequestHash(command.requestHash()),
                command.reasonReference()
        )));
    }

    private PlatformRoleCommandReceipt receipt(IdempotencyExecutionResult result) {
        try {
            PlatformRoleMutationResult mutation = objectMapper.readValue(
                    result.result().responseJson(),
                    PlatformRoleMutationResult.class
            );
            return new PlatformRoleCommandReceipt(new PlatformRoleAssignmentMutation(
                    mutation.assignmentId(),
                    mutation.companyId(),
                    mutation.userId(),
                    PlatformRoleCode.valueOf(mutation.role().name()),
                    PlatformRoleAssignmentStatus.valueOf(mutation.status().name()),
                    mutation.assignmentRowVersion(),
                    mutation.userRowVersion(),
                    mutation.authorizationVersion(),
                    mutation.changedAt()
            ), result.replayed());
        } catch (JacksonException exception) {
            throw new IllegalStateException("platform role result deserialization failed", exception);
        }
    }

    private static RoleCommandActor actor(PlatformRoleCommandActor actor) {
        return new RoleCommandActor(
                actor.userId(),
                actor.sessionAuthorizationVersion(),
                actor.authenticatedAt()
        );
    }
}
