package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.application.authorization.GrantPlatformRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleManagementUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMutationResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class M113PlatformRoleCommandAdapterTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void mapsPublicCommandAndTypedReplayReceiptWithoutLeakingApplicationTypes() throws Exception {
        PlatformRoleManagementUseCase useCase = mock(PlatformRoleManagementUseCase.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformRoleCommandAdapter adapter = new PlatformRoleCommandAdapter(useCase, objectMapper);
        UUID companyId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        Instant changedAt = Instant.parse("2026-08-15T03:00:00Z");
        PlatformRoleMutationResult storedMutation = new PlatformRoleMutationResult(
                assignmentId,
                companyId,
                targetUserId,
                ManagedPlatformRole.COMPANY_ADMIN,
                com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentStatus.ACTIVE,
                1,
                2,
                3,
                changedAt
        );
        StoredCommandResult stored = new StoredCommandResult(
                201,
                objectMapper.writeValueAsString(storedMutation),
                assignmentId,
                "\"1\""
        );
        when(useCase.grant(any())).thenReturn(IdempotencyExecutionResult.replayed(stored));

        PlatformRoleCommandReceipt receipt = adapter.grant(new PlatformRoleGrantCommand(
                companyId,
                targetUserId,
                PlatformRoleCode.COMPANY_ADMIN,
                0,
                new PlatformRoleCommandActor(UUID.randomUUID(), 4, changedAt),
                UUID.randomUUID(),
                HASH,
                "  M1-13 acceptance  "
        ));

        assertThat(receipt.replayed()).isTrue();
        assertThat(receipt.mutation()).isEqualTo(new PlatformRoleAssignmentMutation(
                assignmentId,
                companyId,
                targetUserId,
                PlatformRoleCode.COMPANY_ADMIN,
                PlatformRoleAssignmentStatus.ACTIVE,
                1,
                2,
                3,
                changedAt
        ));
        verify(useCase).grant(any(GrantPlatformRoleCommand.class));
    }

    @Test
    void rejectsNonCanonicalHashesAndReasonsAtThePublicBoundary() {
        PlatformRoleCommandActor actor = new PlatformRoleCommandActor(
                UUID.randomUUID(),
                0,
                Instant.parse("2026-08-15T03:00:00Z")
        );

        assertThatThrownBy(() -> new PlatformRoleGrantCommand(
                UUID.randomUUID(), UUID.randomUUID(), PlatformRoleCode.APP_MANAGER,
                0, actor, UUID.randomUUID(), "A".repeat(64), "reason"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> new PlatformRoleRevokeCommand(
                UUID.randomUUID(), UUID.randomUUID(), PlatformRoleCode.APP_MANAGER,
                0, actor, UUID.randomUUID(), HASH, " "
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonReference");
    }
}
