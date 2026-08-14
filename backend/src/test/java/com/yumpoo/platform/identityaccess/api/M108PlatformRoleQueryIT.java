package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class M108PlatformRoleQueryIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("81000000-0000-4000-8000-000000000108");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformRoleQuery roleQuery;

    @Autowired
    private ActiveUserSnapshotQuery activeUserQuery;

    @BeforeEach
    void setUp() {
        deleteFixture();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, directory_synced_at,
                            authorization_version, row_version, created_at, updated_at
                        ) VALUES (
                            :userId, :companyId, 'ACTIVE', 'ENABLED',
                            'M1-08 Role User', transaction_timestamp(),
                            0, 0, transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("userId", USER_ID)
                .param("companyId", COMPANY_ID)
                .update();
    }

    @AfterEach
    void tearDown() {
        deleteFixture();
    }

    @Test
    void activeRolesAreCombinedAndReturnedAsAnImmutableSet() {
        insertActive(UUID.fromString("81000000-0000-4000-8000-000000000109"), "COMPANY_ADMIN", "COMPANY");
        insertActive(UUID.fromString("81000000-0000-4000-8000-000000000110"), "APP_MANAGER", "PLATFORM");

        Set<PlatformRoleCode> roles = roleQuery.findActiveRoleCodes(COMPANY_ID, USER_ID);

        assertThat(roles).containsExactlyInAnyOrder(
                PlatformRoleCode.COMPANY_ADMIN,
                PlatformRoleCode.APP_MANAGER
        );
        assertThatThrownBy(() -> roles.add(PlatformRoleCode.APP_MANAGER))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void revokedHistoryIsRetainedAndARegrantUsesANewRow() {
        UUID revokedId = UUID.fromString("81000000-0000-4000-8000-000000000111");
        insertActive(revokedId, "COMPANY_ADMIN", "COMPANY");
        jdbcClient.sql("""
                        UPDATE yumpoo.platform_role_assignment
                        SET status = 'REVOKED', revoked_by_user_id = :userId,
                            revoked_at = transaction_timestamp(), revoke_reason = 'M1-08 test revoke',
                            row_version = row_version + 1, updated_at = transaction_timestamp()
                        WHERE id = :id
                        """)
                .param("userId", USER_ID)
                .param("id", revokedId)
                .update();
        assertThat(roleQuery.findActiveRoleCodes(COMPANY_ID, USER_ID)).isEmpty();

        insertActive(UUID.fromString("81000000-0000-4000-8000-000000000112"), "COMPANY_ADMIN", "COMPANY");

        assertThat(roleQuery.findActiveRoleCodes(COMPANY_ID, USER_ID))
                .containsExactly(PlatformRoleCode.COMPANY_ADMIN);
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.platform_role_assignment
                        WHERE user_id = :userId AND role_code = 'COMPANY_ADMIN'
                        """).param("userId", USER_ID).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void activeUniquenessAndRoleScopePairingFailClosed() {
        insertActive(UUID.fromString("81000000-0000-4000-8000-000000000113"), "COMPANY_ADMIN", "COMPANY");
        assertThatThrownBy(() -> insertActive(
                UUID.fromString("81000000-0000-4000-8000-000000000114"),
                "COMPANY_ADMIN",
                "COMPANY"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertActive(
                UUID.fromString("81000000-0000-4000-8000-000000000115"),
                "APP_MANAGER",
                "COMPANY"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertActive(
                UUID.fromString("81000000-0000-4000-8000-000000000116"),
                "UNKNOWN",
                "PLATFORM"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void crossCompanyReferencesAndInvalidLifecycleAreRejected() {
        UUID otherCompany = UUID.fromString("90000000-0000-4000-8000-000000000001");
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO yumpoo.platform_role_assignment (
                            id, company_id, user_id, role_code, scope_type, scope_id, status,
                            granted_by_actor_type, granted_by_system_code, grant_reason,
                            granted_at, row_version, created_at, updated_at
                        ) VALUES (
                            :id, :otherCompany, :userId, 'APP_MANAGER', 'PLATFORM', :otherCompany,
                            'ACTIVE', 'SYSTEM', 'M1_08_TEST', 'cross-company fixture',
                            transaction_timestamp(), 0, transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("id", UUID.fromString("81000000-0000-4000-8000-000000000117"))
                .param("otherCompany", otherCompany)
                .param("userId", USER_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO yumpoo.platform_role_assignment (
                            id, company_id, user_id, role_code, scope_type, scope_id, status,
                            granted_by_actor_type, granted_by_system_code, grant_reason,
                            granted_at, revoke_reason, row_version, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, :userId, 'APP_MANAGER', 'PLATFORM', :companyId,
                            'ACTIVE', 'SYSTEM', 'M1_08_TEST', 'invalid lifecycle',
                            transaction_timestamp(), 'must be absent', 0,
                            transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("id", UUID.fromString("81000000-0000-4000-8000-000000000118"))
                .param("companyId", COMPANY_ID)
                .param("userId", USER_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void roleFactsSurviveLeftAndDisabledWhileActiveUserSnapshotRejectsThePrincipal() {
        insertActive(UUID.fromString("81000000-0000-4000-8000-000000000119"), "APP_MANAGER", "PLATFORM");
        jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET employment_status = 'LEFT', left_at = transaction_timestamp(),
                            left_reason = 'M1-08 test', account_status = 'DISABLED',
                            account_disabled_at = transaction_timestamp(),
                            account_disabled_by_user_id = id,
                            account_disabled_reason = 'M1-08 test',
                            authorization_version = authorization_version + 1,
                            updated_at = transaction_timestamp()
                        WHERE id = :userId
                        """).param("userId", USER_ID).update();

        assertThat(roleQuery.findActiveRoleCodes(COMPANY_ID, USER_ID))
                .containsExactly(PlatformRoleCode.APP_MANAGER);
        assertThat(activeUserQuery.findByUserId(USER_ID)).hasValueSatisfying(snapshot ->
                assertThat(snapshot.activeAndEnabled()).isFalse());
    }

    private void insertActive(UUID id, String role, String scope) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.platform_role_assignment (
                            id, company_id, user_id, role_code, scope_type, scope_id, status,
                            granted_by_actor_type, granted_by_system_code, grant_reason,
                            granted_at, row_version, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, :userId, :role, :scope, :companyId, 'ACTIVE',
                            'SYSTEM', 'M1_08_TEST', 'integration fixture',
                            transaction_timestamp(), 0, transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("id", id)
                .param("companyId", COMPANY_ID)
                .param("userId", USER_ID)
                .param("role", role)
                .param("scope", scope)
                .update();
    }

    private void deleteFixture() {
        jdbcClient.sql("DELETE FROM yumpoo.platform_role_assignment WHERE user_id = :userId")
                .param("userId", USER_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE id = :userId")
                .param("userId", USER_ID)
                .update();
    }
}
