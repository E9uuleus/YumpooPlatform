package com.yumpoo.platform.catalog.infrastructure.workspace;

import com.yumpoo.platform.catalog.api.WorkspaceSnapshotQuery;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceListStatus;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceService;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceUpdateCommand;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceView;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkspaceCatalogIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000099");
    private static final UUID ACTOR_ID = UUID.fromString("17000000-0000-4000-8000-000000000001");

    @Autowired private WorkspaceService service;
    @Autowired private WorkspaceSnapshotQuery snapshots;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void insertActor() {
        jdbc.sql("""
                INSERT INTO yumpoo.identity_user (
                    id, company_id, employment_status, account_status, display_name,
                    directory_synced_at, authorization_version, row_version, created_at, updated_at
                ) VALUES (:id, :companyId, 'ACTIVE', 'ENABLED', 'MAIN Workspace Admin',
                    transaction_timestamp(), 0, 0, transaction_timestamp(), transaction_timestamp())
                ON CONFLICT (id) DO NOTHING
                """).param("id", ACTOR_ID).param("companyId", COMPANY_ID).update();
    }

    @Test
    @Transactional
    void companyHasExactlyOneStableActiveMainWorkspace() {
        assertThat(service.findAll(member(), WorkspaceListStatus.ACTIVE))
                .singleElement()
                .satisfies(workspace -> {
                    assertThat(workspace.code()).isEqualTo("MAIN");
                    assertThat(workspace.sortOrder()).isZero();
                    assertThat(workspace.status()).isEqualTo(WorkspaceStatus.ACTIVE);
                    assertThat(snapshots.findActive(COMPANY_ID, workspace.id())).isPresent();
                    assertThat(snapshots.findActive(OTHER_COMPANY_ID, workspace.id())).isEmpty();
                });
    }

    @Test
    @Transactional
    void administratorPatchPreservesMainFactsAndRecordsUpdater() {
        WorkspaceView before = service.findAll(admin(), WorkspaceListStatus.ACTIVE).getFirst();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("main-workspace-patch"))) {
            WorkspaceView after = service.update(new WorkspaceUpdateCommand(
                    admin(), before.id(), before.rowVersion(), "研发主工作空间", "统一项目归属"));
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.code()).isEqualTo("MAIN");
            assertThat(after.sortOrder()).isZero();
            assertThat(after.status()).isEqualTo(WorkspaceStatus.ACTIVE);
            assertThat(after.rowVersion()).isEqualTo(before.rowVersion() + 1);
            assertThat(jdbc.sql("SELECT updated_by_user_id FROM yumpoo.workspace WHERE id=:id")
                    .param("id", before.id()).query(UUID.class).single()).isEqualTo(ACTOR_ID);
        }
    }

    @Test
    @Transactional
    void databaseRejectsSecondWorkspaceAndMutableMainFacts() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO yumpoo.workspace (
                    id, company_id, code, name, sort_order, status, row_version,
                    created_at, updated_at
                ) VALUES (:id, :companyId, 'MAIN', '第二空间', 0, 'ACTIVE', 0,
                    transaction_timestamp(), transaction_timestamp())
                """).param("id", UUID.randomUUID()).param("companyId", COMPANY_ID).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void databaseRejectsRemovingTheCompanyMainWorkspace() {
        jdbc.sql("DELETE FROM yumpoo.workspace WHERE company_id=:companyId")
                .param("companyId", COMPANY_ID).update();
        assertThatThrownBy(() -> jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void memberCannotPatchMainWorkspace() {
        UUID workspaceId = service.findAll(member(), WorkspaceListStatus.ACTIVE).getFirst().id();
        assertThatThrownBy(() -> service.update(new WorkspaceUpdateCommand(
                member(), workspaceId, 0, "无权限", null)))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
    }

    private static CurrentActor admin() {
        return new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    }

    private static CurrentActor member() {
        return new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of());
    }
}
