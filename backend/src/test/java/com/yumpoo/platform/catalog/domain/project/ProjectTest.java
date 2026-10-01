package com.yumpoo.platform.catalog.domain.project;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");

    @Test
    void createNormalizesOptionalTextAndStartsActive() {
        Project project = create("  Project description  ");

        assertThat(project.description()).isEqualTo("Project description");
        assertThat(project.lifecycle()).isEqualTo(ProjectLifecycle.ACTIVE);
        assertThat(project.rowVersion()).isZero();
        assertThat(project.archivedAt()).isNull();
    }

    @Test
    void fieldLimitsAreRejected() {
        assertThatThrownBy(() -> create("x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Project.create(UUID.randomUUID(), COMPANY_ID, WORKSPACE_ID,
                "lower", "Project", null, OWNER_ID, OWNER_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void membershipRemovalAndReactivationReuseIdentityAndAdvanceVersion() {
        ProjectMembership active = ProjectMembership.activeMember(UUID.randomUUID(), COMPANY_ID,
                UUID.randomUUID(), UUID.randomUUID(), OWNER_ID, NOW);
        ProjectMembership removed = active.remove(OWNER_ID, null, NOW.plusSeconds(10));
        ProjectMembership reactivated = removed.reactivate(OWNER_ID, NOW.plusSeconds(20));

        assertThat(removed.status()).isEqualTo(ProjectMembershipStatus.REMOVED);
        assertThat(removed.removeReason()).isNull();
        assertThat(reactivated.id()).isEqualTo(active.id());
        assertThat(reactivated.status()).isEqualTo(ProjectMembershipStatus.ACTIVE);
        assertThat(reactivated.rowVersion()).isEqualTo(2);
        assertThat(reactivated.removedAt()).isNull();
    }

    @Test
    void archivedProjectCannotReassignOwner() {
        Project draft = create(null);
        Project archived = new Project(draft.id(), draft.companyId(), draft.workspaceId(), draft.code(),
                draft.name(), draft.description(), ProjectLifecycle.ARCHIVED,
                draft.ownerUserId(), 2, draft.createdAt(),
                draft.createdByUserId(), NOW.plusSeconds(20), OWNER_ID, NOW.plusSeconds(20));
        assertThatThrownBy(() -> archived.reassignOwner(UUID.randomUUID(), OWNER_ID, NOW.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateNormalizesFullSnapshotAndNoChangeCanBeDetected() {
        Project draft = create(null);
        Project updated = draft.updateDetails("  新名称  ", "  描述  ", OWNER_ID, NOW.plusSeconds(10));

        assertThat(updated.name()).isEqualTo("新名称");
        assertThat(updated.rowVersion()).isOne();
        assertThat(updated.hasSameDetails("新名称", "描述")).isTrue();
    }

    @Test
    void archiveAndReopenAdvanceVersion() {
        Project active = create(null);

        Project archived = active.archive(OWNER_ID, NOW.plusSeconds(20));
        Project reopened = archived.reopen(OWNER_ID, NOW.plusSeconds(30));

        assertThat(archived.lifecycle()).isEqualTo(ProjectLifecycle.ARCHIVED);
        assertThat(archived.archivedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(reopened.lifecycle()).isEqualTo(ProjectLifecycle.ACTIVE);
        assertThat(reopened.archivedAt()).isNull();
        assertThat(reopened.rowVersion()).isEqualTo(2);
    }

    @Test
    void archiveAndRestoreRejectInvalidLifecycle() {
        Project draft = create(null);
        Project active = draft;
        Project archived = active.archive(OWNER_ID, NOW.plusSeconds(20));

        assertThatThrownBy(() -> archived.archive(OWNER_ID, NOW.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> active.reopen(OWNER_ID, NOW.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Project create(String description) {
        return Project.create(UUID.randomUUID(), COMPANY_ID, WORKSPACE_ID, "M2_04",
                " M2-04 Project ", description, OWNER_ID, OWNER_ID, NOW);
    }
}
