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
    void createNormalizesOptionalTextAndStartsAsDraft() {
        Project project = create(ProjectType.PRODUCT_DEVELOPMENT, "RND",
                "  Project description  ", "  Customer  ", "   ");

        assertThat(project.description()).isEqualTo("Project description");
        assertThat(project.customerName()).isEqualTo("Customer");
        assertThat(project.customerReference()).isNull();
        assertThat(project.lifecycle()).isEqualTo(ProjectLifecycle.DRAFT);
        assertThat(project.rowVersion()).isZero();
        assertThat(project.activatedAt()).isNull();
    }

    @Test
    void allFrozenProjectTypesMapToExactlyOneTemplateKey() {
        assertThat(ProjectType.PRODUCT_DEVELOPMENT.templateKey()).isEqualTo("RND");
        assertThat(ProjectType.PRE_SALES.templateKey()).isEqualTo("PRE_SALES");
        assertThat(ProjectType.IMPLEMENTATION.templateKey()).isEqualTo("IMPLEMENTATION");
        assertThat(ProjectType.HYPERCARE.templateKey()).isEqualTo("HYPERCARE");
    }

    @Test
    void typeMismatchAndFieldLimitsAreRejected() {
        assertThatThrownBy(() -> create(ProjectType.PRODUCT_DEVELOPMENT, "PRE_SALES",
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(ProjectType.PRE_SALES, "PRE_SALES",
                "x".repeat(501), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Project.create(UUID.randomUUID(), COMPANY_ID, WORKSPACE_ID,
                "lower", "Project", null, ProjectType.PRE_SALES, OWNER_ID,
                "PRE_SALES", 1, null, null, null, null, OWNER_ID, NOW))
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
        Project draft = create(ProjectType.PRODUCT_DEVELOPMENT, "RND", null, null, null);
        Project archived = new Project(draft.id(), draft.companyId(), draft.workspaceId(), draft.code(),
                draft.name(), draft.description(), draft.projectType(), ProjectLifecycle.ARCHIVED,
                draft.ownerUserId(), draft.templateKey(), draft.templateVersion(), draft.customerName(),
                draft.customerReference(), draft.deliverySite(), draft.contactNote(), 2, draft.createdAt(),
                draft.createdByUserId(), NOW.plusSeconds(20), OWNER_ID, NOW.plusSeconds(10), NOW.plusSeconds(20));
        assertThatThrownBy(() -> archived.reassignOwner(UUID.randomUUID(), OWNER_ID, NOW.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateNormalizesFullSnapshotAndNoChangeCanBeDetected() {
        Project draft = create(ProjectType.PRE_SALES, "PRE_SALES", null, null, null);
        Project updated = draft.updateDetails("  新名称  ", "  描述  ", "  客户  ",
                " ", " 上海 ", " 联系备注 ", OWNER_ID, NOW.plusSeconds(10));

        assertThat(updated.name()).isEqualTo("新名称");
        assertThat(updated.customerReference()).isNull();
        assertThat(updated.rowVersion()).isOne();
        assertThat(updated.hasSameDetails("新名称", "描述", "客户", null,
                "上海", "联系备注")).isTrue();
    }

    @Test
    void activationOnlyAllowsDraftAndCapturesLifecycleTimestamp() {
        Project draft = create(ProjectType.PRODUCT_DEVELOPMENT, "RND", null, null, null);
        Project active = draft.activate(OWNER_ID, NOW.plusSeconds(10));

        assertThat(active.lifecycle()).isEqualTo(ProjectLifecycle.ACTIVE);
        assertThat(active.activatedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(active.rowVersion()).isOne();
        assertThatThrownBy(() -> active.activate(OWNER_ID, NOW.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void archiveAndReopenPreserveActivationTimeAndAdvanceVersion() {
        Project active = create(ProjectType.PRODUCT_DEVELOPMENT, "RND", null, null, null)
                .activate(OWNER_ID, NOW.plusSeconds(10));

        Project archived = active.archive(OWNER_ID, NOW.plusSeconds(20));
        Project reopened = archived.reopen(OWNER_ID, NOW.plusSeconds(30));

        assertThat(archived.lifecycle()).isEqualTo(ProjectLifecycle.ARCHIVED);
        assertThat(archived.archivedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(reopened.lifecycle()).isEqualTo(ProjectLifecycle.ACTIVE);
        assertThat(reopened.activatedAt()).isEqualTo(active.activatedAt());
        assertThat(reopened.archivedAt()).isNull();
        assertThat(reopened.rowVersion()).isEqualTo(3);
    }

    @Test
    void archiveRestoreAndMoveRejectInvalidLifecycleOrUnchangedWorkspace() {
        Project draft = create(ProjectType.PRODUCT_DEVELOPMENT, "RND", null, null, null);
        Project active = draft.activate(OWNER_ID, NOW.plusSeconds(10));
        Project archived = active.archive(OWNER_ID, NOW.plusSeconds(20));

        assertThatThrownBy(() -> draft.archive(OWNER_ID, NOW.plusSeconds(5)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> active.reopen(OWNER_ID, NOW.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> archived.moveToWorkspace(UUID.randomUUID(), OWNER_ID,
                NOW.plusSeconds(30))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> active.moveToWorkspace(WORKSPACE_ID, OWNER_ID,
                NOW.plusSeconds(30))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void workspaceMoveChangesOnlyWorkspaceAndAuditFields() {
        Project active = create(ProjectType.PRE_SALES, "PRE_SALES", "描述", "客户", "C-1")
                .activate(OWNER_ID, NOW.plusSeconds(10));
        UUID targetWorkspaceId = UUID.randomUUID();

        Project moved = active.moveToWorkspace(targetWorkspaceId, OWNER_ID, NOW.plusSeconds(20));

        assertThat(moved.workspaceId()).isEqualTo(targetWorkspaceId);
        assertThat(moved.id()).isEqualTo(active.id());
        assertThat(moved.companyId()).isEqualTo(active.companyId());
        assertThat(moved.code()).isEqualTo(active.code());
        assertThat(moved.lifecycle()).isEqualTo(active.lifecycle());
        assertThat(moved.ownerUserId()).isEqualTo(active.ownerUserId());
        assertThat(moved.templateKey()).isEqualTo(active.templateKey());
        assertThat(moved.rowVersion()).isEqualTo(active.rowVersion() + 1);
        assertThat(moved.updatedAt()).isEqualTo(NOW.plusSeconds(20));
    }

    private static Project create(
            ProjectType type,
            String templateKey,
            String description,
            String customerName,
            String customerReference
    ) {
        return Project.create(UUID.randomUUID(), COMPANY_ID, WORKSPACE_ID, "M2_04",
                " M2-04 Project ", description, type, OWNER_ID, templateKey, 1,
                customerName, customerReference, " ", " ", OWNER_ID, NOW);
    }
}
