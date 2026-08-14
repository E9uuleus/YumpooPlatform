package com.yumpoo.platform.identityaccess.infrastructure.identity;

import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningService;
import com.yumpoo.platform.identityaccess.application.directory.WeComMemberProfile;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryOptionalField;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.identityaccess.domain.identity.ProfileHash;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DirectoryMemberProvisioningIT {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );

    @Autowired
    private DirectoryMemberProvisioningService service;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    @AfterEach
    void removeIdentityFacts() {
        jdbcClient.sql("DELETE FROM yumpoo.external_identity").update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user").update();
    }

    @Test
    void createsThenRefreshesTheSameStableBinding() {
        DirectoryMemberProvisioningResult created = service.provisionOrRefresh(profile(
                "Member-A",
                "Alice",
                "13800000000",
                "a"
        ));
        DirectoryMemberProvisioningResult refreshed = service.provisionOrRefresh(profile(
                "Member-A",
                "Alice Renamed",
                "13900000000",
                "b"
        ));

        assertThat(created.created()).isTrue();
        assertThat(created.profileChanged()).isTrue();
        assertThat(created.employmentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(created.accountStatus()).isEqualTo(AccountStatus.ENABLED);
        assertThat(refreshed.userId()).isEqualTo(created.userId());
        assertThat(refreshed.externalIdentityId()).isEqualTo(created.externalIdentityId());
        assertThat(refreshed.created()).isFalse();
        assertThat(refreshed.profileChanged()).isTrue();
        assertThat(refreshed.rowVersion()).isEqualTo(1);
        assertThat(count("identity_user")).isOne();
        assertThat(count("external_identity")).isOne();
        assertThat(jdbcClient.sql("""
                        SELECT display_name || '|' || mobile
                        FROM yumpoo.identity_user
                        WHERE id = :id
                        """)
                .param("id", created.userId())
                .query(String.class)
                .single()).isEqualTo("Alice Renamed|13900000000");
    }

    @Test
    void doesNotMergeDifferentExternalIdsWithMatchingProfileFields() {
        DirectoryMemberProvisioningResult first = service.provisionOrRefresh(profile(
                "member-a",
                "Same Name",
                "13800000000",
                "a"
        ));
        DirectoryMemberProvisioningResult second = service.provisionOrRefresh(profile(
                "member-b",
                "Same Name",
                "13800000000",
                "a"
        ));

        assertThat(second.userId()).isNotEqualTo(first.userId());
        assertThat(count("identity_user")).isEqualTo(2);
        assertThat(count("external_identity")).isEqualTo(2);
    }

    @Test
    void refreshPreservesEmploymentAndAccountStatusesAndTheirHistory() {
        DirectoryMemberProvisioningResult created = service.provisionOrRefresh(profile(
                "member-status",
                "Before",
                null,
                "a"
        ));
        jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET employment_status = 'LEFT',
                            account_status = 'DISABLED',
                            left_at = transaction_timestamp(),
                            left_reason = 'DIRECTORY_CONFIRMED',
                            account_disabled_at = transaction_timestamp(),
                            account_disabled_by_user_id = id,
                            account_disabled_reason = 'SECURITY_REVIEW',
                            updated_at = transaction_timestamp(),
                            row_version = row_version + 1
                        WHERE id = :id
                        """)
                .param("id", created.userId())
                .update();

        DirectoryMemberProvisioningResult refreshed = service.provisionOrRefresh(profile(
                "member-status",
                "After",
                "13900000000",
                "b"
        ));

        assertThat(refreshed.employmentStatus()).isEqualTo(EmploymentStatus.LEFT);
        assertThat(refreshed.accountStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(jdbcClient.sql("""
                        SELECT employment_status || '|' || account_status || '|'
                            || left_reason || '|' || account_disabled_reason
                        FROM yumpoo.identity_user
                        WHERE id = :id
                        """)
                .param("id", created.userId())
                .query(String.class)
                .single()).isEqualTo(
                        "LEFT|DISABLED|DIRECTORY_CONFIRMED|SECURITY_REVIEW"
                );
    }

    @Test
    void databaseRejectsDuplicateBindingsCrossCompanyReferencesAndInvalidCodes() {
        DirectoryMemberProvisioningResult first = service.provisionOrRefresh(profile(
                "member-a",
                "Alice",
                null,
                "a"
        ));
        UUID otherUserId = UUID.randomUUID();
        insertActiveUser(otherUserId, "Other");

        assertThatThrownBy(() -> insertExternalIdentity(
                UUID.randomUUID(),
                otherUserId,
                "member-a",
                "WECOM",
                "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertExternalIdentity(
                UUID.randomUUID(),
                first.userId(),
                "member-c",
                "WECOM",
                "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertExternalIdentity(
                UUID.randomUUID(),
                otherUserId,
                "member-invalid-provider",
                "OTHER",
                "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertExternalIdentity(
                UUID.randomUUID(),
                otherUserId,
                "member-invalid-hash",
                "WECOM",
                "not-a-sha256"
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, directory_synced_at, row_version,
                            created_at, updated_at
                        ) VALUES (
                            :id, :companyId, 'UNKNOWN', 'ENABLED',
                            'Invalid', :now, 0, :now, :now
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("companyId", COMPANY_ID)
                .param("now", databaseInstant())
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentProvisioningLeavesExactlyOneBindingAndNoOrphanUser() throws Exception {
        int callers = 6;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DirectoryMemberProvisioningResult>> futures = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(callers)) {
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.provisionOrRefresh(profile(
                            "member-concurrent",
                            "Concurrent",
                            null,
                            "c"
                    ));
                }));
            }
            ready.await();
            start.countDown();

            List<DirectoryMemberProvisioningResult> results = new ArrayList<>();
            for (Future<DirectoryMemberProvisioningResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results).extracting(DirectoryMemberProvisioningResult::userId)
                    .containsOnly(results.getFirst().userId());
            assertThat(results).extracting(
                            DirectoryMemberProvisioningResult::externalIdentityId
                    )
                    .containsOnly(results.getFirst().externalIdentityId());
            assertThat(results).filteredOn(DirectoryMemberProvisioningResult::created)
                    .hasSize(1);
            assertThat(results).filteredOn(result -> !result.created())
                    .allSatisfy(result -> assertThat(result.profileChanged()).isFalse());
            assertThat(count("identity_user")).isOne();
            assertThat(count("external_identity")).isOne();
        }
    }

    private WeComMemberProfile profile(
            String externalUserId,
            String displayName,
            String mobile,
            String hashCharacter
    ) {
        return new WeComMemberProfile(
                externalUserId,
                displayName,
                DirectoryOptionalField.present(displayName.toLowerCase().replace(' ', '.') + "@example.test"),
                mobile == null
                        ? DirectoryOptionalField.clear()
                        : DirectoryOptionalField.present(mobile),
                "Engineering",
                new ProfileHash(hashCharacter.repeat(64))
        );
    }

    private int count(String tableName) {
        if (!Set.of("identity_user", "external_identity").contains(tableName)) {
            throw new IllegalArgumentException("Unsupported table");
        }
        return jdbcClient.sql("SELECT count(*) FROM yumpoo." + tableName)
                .query(Integer.class)
                .single();
    }

    private void insertActiveUser(UUID userId, String displayName) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, directory_synced_at, row_version,
                            created_at, updated_at
                        ) VALUES (
                            :id, :companyId, 'ACTIVE', 'ENABLED',
                            :displayName, :now, 0, :now, :now
                        )
                        """)
                .param("id", userId)
                .param("companyId", COMPANY_ID)
                .param("displayName", displayName)
                .param("now", databaseInstant())
                .update();
    }

    private void insertExternalIdentity(
            UUID identityId,
            UUID userId,
            String externalUserId,
            String provider,
            String hash
    ) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.external_identity (
                            id, company_id, user_id, provider, external_user_id,
                            provider_employment_status, raw_profile_hash,
                            last_seen_at, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, :userId, :provider, :externalUserId,
                            'ACTIVE', :hash, :now, :now, :now
                        )
                        """)
                .param("id", identityId)
                .param("companyId", COMPANY_ID)
                .param("userId", userId)
                .param("provider", provider)
                .param("externalUserId", externalUserId)
                .param("hash", hash)
                .param("now", databaseInstant())
                .update();
    }

    private static OffsetDateTime databaseInstant() {
        return OffsetDateTime.ofInstant(
                Instant.parse("2026-08-13T12:00:00Z"),
                ZoneOffset.UTC
        );
    }
}
