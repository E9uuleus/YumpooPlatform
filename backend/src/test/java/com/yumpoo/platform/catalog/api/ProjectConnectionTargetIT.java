package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "yumpoo.outbox.enabled=false")
@Transactional
class ProjectConnectionTargetIT {
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    @Autowired private ProjectConnectionTargetQuery targets;
    @Autowired private JdbcClient jdbc;
    private UUID owner;
    private String prefix;

    @BeforeEach
    void setUp() {
        owner = UUID.randomUUID();
        prefix = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        jdbc.sql("""
                INSERT INTO yumpoo.identity_user (id, company_id, employment_status, account_status,
                    display_name, directory_synced_at, row_version, created_at, updated_at)
                VALUES (:id, :company, 'ACTIVE', 'ENABLED', '连接目录负责人', now(), 0, now(), now())
                """).param("id", owner).param("company", COMPANY).update();
    }

    @Test
    void searchesCompanyActiveProjectsWithoutMembershipAndWithStablePagination() {
        UUID second = project("B", "相同名称", false);
        UUID first = project("A", "相同名称", false);
        project("C", "归档项目", true);
        var page = targets.searchActive(COMPANY, prefix, new OffsetPageRequest(0, 1));
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(ProjectConnectionTargetQuery.ConnectTargetProject::id)
                .containsExactly(first);
        assertThat(targets.searchActive(COMPANY, prefix, new OffsetPageRequest(1, 1)).items())
                .extracting(ProjectConnectionTargetQuery.ConnectTargetProject::id).containsExactly(second);
        assertThat(targets.searchActive(UUID.randomUUID(), prefix, new OffsetPageRequest(0, 20)).items())
                .isEmpty();
        assertThat(targets.searchActive(COMPANY, "%", new OffsetPageRequest(0, 20)).items()).isEmpty();
    }

    @Test
    void batchSnapshotsIncludeArchivedTargetsButNeverAnotherCompany() {
        UUID active = project("A", "进行中", false);
        UUID archived = project("B", "已归档", true);
        var result = targets.findByIds(COMPANY, List.of(active, archived, UUID.randomUUID()));
        assertThat(result).containsOnlyKeys(active, archived);
        assertThat(result.get(archived).lifecycle()).isEqualTo(ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED);
        assertThat(targets.findByIds(UUID.randomUUID(), List.of(active))).isEmpty();
        assertThat(targets.findByIds(COMPANY, List.of())).isEmpty();
    }

    @Test
    void targetLockUsesLifecycleGuardWithoutRequiringTargetMembership() {
        UUID active = project("A", "进行中", false);
        UUID archived = project("B", "已归档", true);
        assertThat(targets.lockAsConnectionTarget(COMPANY, active).projectId()).isEqualTo(active);
        assertThatThrownBy(() -> targets.lockAsConnectionTarget(COMPANY, archived))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.reason()).isEqualTo("PROJECT_ARCHIVED"));
        assertThatThrownBy(() -> targets.lockAsConnectionTarget(UUID.randomUUID(), active))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void rejectsOverlongSearchAndOversizedPage() {
        assertThatThrownBy(() -> targets.searchActive(COMPANY, "a".repeat(81), new OffsetPageRequest(0, 20)))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> targets.searchActive(COMPANY, "", new OffsetPageRequest(0, 51)))
                .isInstanceOf(ApplicationException.class);
    }

    private UUID project(String suffix, String name, boolean archived) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.project (id, company_id, workspace_id, project_code, name,
                    lifecycle, owner_user_id, row_version, created_at, created_by_user_id,
                    updated_at, updated_by_user_id, archived_at)
                SELECT :id, :company, id, :code, :name, :lifecycle, :owner, 0,
                    now(), :owner, now(), :owner, CASE WHEN :archived THEN now() ELSE NULL END
                FROM yumpoo.workspace WHERE company_id=:company AND code='MAIN'
                """).param("id", id).param("company", COMPANY).param("code", prefix + suffix)
                .param("name", prefix + name).param("lifecycle", archived ? "ARCHIVED" : "ACTIVE")
                .param("owner", owner).param("archived", archived).update();
        jdbc.sql("""
                INSERT INTO yumpoo.project_membership (id, company_id, project_id, user_id, status,
                    joined_at, joined_by_user_id, row_version)
                VALUES (:id, :company, :project, :owner, 'ACTIVE', now(), :owner, 0)
                """).param("id", UUID.randomUUID()).param("company", COMPANY).param("project", id)
                .param("owner", owner).update();
        return id;
    }
}
