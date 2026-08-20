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
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void createsActiveWorkspaceAndNormalizesText() {
        Workspace workspace = Workspace.create(
                WORKSPACE_ID, COMPANY_ID, "DELIVERY", "  交付空间  ", "   ", 10, ACTOR_ID, NOW);

        assertThat(workspace.name()).isEqualTo("交付空间");
        assertThat(workspace.description()).isNull();
        assertThat(workspace.status()).isEqualTo(WorkspaceStatus.ACTIVE);
        assertThat(workspace.rowVersion()).isZero();
    }

    @Test
    void rejectsCallerCodesThatAreNotStableUppercaseIdentifiers() {
        assertThatThrownBy(() -> Workspace.create(
                WORKSPACE_ID, COMPANY_ID, "delivery", "交付空间", null, 0, ACTOR_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stable uppercase identifier");
    }

    @Test
    void recognizesNoChangeAfterNormalization() {
        Workspace workspace = Workspace.create(
                WORKSPACE_ID, COMPANY_ID, "DELIVERY", "交付空间", null, 10, ACTOR_ID, NOW);

        assertThat(workspace.hasSameDetails("  交付空间 ", "  ", 10)).isTrue();
    }

    @Test
    void updatesMutableDetailsWithoutChangingCodeAndAdvancesVersion() {
        Workspace before = Workspace.create(
                WORKSPACE_ID, COMPANY_ID, "DELIVERY", "交付空间", null, 10, ACTOR_ID, NOW);
        UUID updater = UUID.fromString("10000000-0000-4000-8000-000000000004");

        Workspace after = before.updateDetails(
                "产品空间", "团队项目目录", 20, updater, NOW.plusSeconds(1));

        assertThat(after.code()).isEqualTo("DELIVERY");
        assertThat(after.name()).isEqualTo("产品空间");
        assertThat(after.description()).isEqualTo("团队项目目录");
        assertThat(after.sortOrder()).isEqualTo(20);
        assertThat(after.rowVersion()).isOne();
        assertThat(after.updatedByUserId()).isEqualTo(updater);
    }

    @Test
    void lifecycleChangeAdvancesVersion() {
        Workspace before = Workspace.create(
                WORKSPACE_ID, COMPANY_ID, "DELIVERY", "交付空间", null, 10, ACTOR_ID, NOW);

        Workspace archived = before.changeStatus(WorkspaceStatus.ARCHIVED, ACTOR_ID, NOW.plusSeconds(1));

        assertThat(archived.status()).isEqualTo(WorkspaceStatus.ARCHIVED);
        assertThat(archived.rowVersion()).isOne();
    }
}
