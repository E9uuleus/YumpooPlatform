package com.yumpoo.platform.catalog.domain.workspace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void systemProvisionedMainAllowsNullAuditActors() {
        Workspace workspace = main(null, null);

        assertThat(workspace.code()).isEqualTo("MAIN");
        assertThat(workspace.status()).isEqualTo(WorkspaceStatus.ACTIVE);
        assertThat(workspace.createdByUserId()).isNull();
    }

    @Test
    void recognizesNoChangeAfterNormalization() {
        assertThat(main(null, null).hasSameDetails("  主工作空间 ", "  ")).isTrue();
    }

    @Test
    void patchRecordsUpdaterWithoutChangingStableIdentity() {
        Workspace before = main(null, null);
        Workspace after = before.updateDetails("研发空间", "统一归属", ACTOR_ID, NOW.plusSeconds(1));

        assertThat(after.id()).isEqualTo(WORKSPACE_ID);
        assertThat(after.code()).isEqualTo("MAIN");
        assertThat(after.sortOrder()).isZero();
        assertThat(after.status()).isEqualTo(WorkspaceStatus.ACTIVE);
        assertThat(after.rowVersion()).isOne();
        assertThat(after.updatedByUserId()).isEqualTo(ACTOR_ID);
    }

    @Test
    void rejectsNonStableCodesAtTheDomainBoundary() {
        assertThatThrownBy(() -> new Workspace(WORKSPACE_ID, COMPANY_ID, "main", "主空间", null,
                0, WorkspaceStatus.ACTIVE, 0, NOW, null, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stable uppercase identifier");
    }

    private static Workspace main(UUID createdBy, UUID updatedBy) {
        return new Workspace(WORKSPACE_ID, COMPANY_ID, "MAIN", "主工作空间", null, 0,
                WorkspaceStatus.ACTIVE, 0, NOW, createdBy, NOW, updatedBy);
    }
}
