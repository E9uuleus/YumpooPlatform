package com.yumpoo.platform.identityaccess.infrastructure.identity;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryCanonicalHash;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryOptionalField;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryScanResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncClaim;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncCommand;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncItemApplyService;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncLeaseLostException;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRepository;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunSnapshot;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunStatus;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncTriggerType;
import com.yumpoo.platform.identityaccess.application.directory.WeComMemberProfile;
import com.yumpoo.platform.identityaccess.domain.identity.ProfileHash;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DirectorySyncRepositoryIT {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private DirectorySyncRepository repository;

    @Autowired
    private DirectorySyncItemApplyService itemApplyService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    @AfterEach
    void removeDirectoryFacts() {
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID)
                .update();
        jdbcClient.sql("""
                        DELETE FROM yumpoo.outbox_event
                        WHERE event_type LIKE 'identity.directory_sync_%'
                           OR event_type LIKE 'identity.user_employment_%'
                           OR event_type = 'identity.user_sessions_revoked'
                        """)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.directory_sync_run").update();
        jdbcClient.sql("DELETE FROM yumpoo.login_session").update();
        jdbcClient.sql("DELETE FROM yumpoo.external_identity").update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user").update();
    }

    @Test
    void importsThenUpdatesStableMemberWithoutDuplicatingUser() {
        DirectorySyncRunSnapshot created = execute(
                "m104-run-created",
                "Alice",
                DirectoryOptionalField.present("alice@example.test"),
                "a"
        );
        UUID userId = singleUserId();

        DirectorySyncRunSnapshot unavailable = execute(
                "m104-run-unavailable",
                "Alice Renamed",
                DirectoryOptionalField.unavailable(),
                "b"
        );
        DirectorySyncRunSnapshot cleared = execute(
                "m104-run-cleared",
                "Alice Renamed",
                DirectoryOptionalField.clear(),
                "c"
        );

        assertThat(created.status()).isEqualTo(DirectorySyncRunStatus.SUCCEEDED);
        assertThat(created.counts().created()).isOne();
        assertThat(unavailable.counts().updated()).isOne();
        assertThat(cleared.counts().updated()).isOne();
        assertThat(singleUserId()).isEqualTo(userId);
        assertThat(count("identity_user")).isOne();
        assertThat(count("external_identity")).isOne();
        assertThat(jdbcClient.sql("SELECT email FROM yumpoo.identity_user WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional()).isEmpty();
        assertThat(count("directory_sync_staging_member")).isZero();
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.directory_sync_run
                        WHERE status = 'SUCCEEDED' AND provider_cursor IS NULL
                        """).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.outbox_event
                        WHERE event_type = 'identity.directory_sync_completed'
                        """).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbcClient.sql("""
                        SELECT bool_and(
                            jsonb_exists(payload_json, 'stagedCount')
                            AND jsonb_exists(payload_json, 'notAppliedCount')
                            AND payload_json::text NOT LIKE '%member-a%'
                            AND payload_json::text NOT LIKE '%Alice%'
                        )
                        FROM yumpoo.outbox_event
                        WHERE event_type LIKE 'identity.directory_sync_%'
                        """).query(Boolean.class).single()).isTrue();
    }

    @Test
    void replaysSameTriggerAndReturnsActiveRunForDifferentTrigger() {
        try (RequestCorrelationContext.Scope ignored = correlation("m104-active-context")) {
            DirectorySyncCommand first = command("m104-active-a");
            DirectorySyncClaim owner = repository.claim(COMPANY_ID, first, LEASE);

            DirectorySyncClaim replay = repository.claim(COMPANY_ID, first, LEASE);
            DirectorySyncClaim different = repository.claim(
                    COMPANY_ID,
                    command("m104-active-b"),
                    LEASE
            );

            assertThat(owner.executionOwner()).isTrue();
            assertThat(replay.executionOwner()).isFalse();
            assertThat(different.executionOwner()).isFalse();
            assertThat(replay.snapshot().runId()).isEqualTo(owner.snapshot().runId());
            assertThat(different.snapshot().runId()).isEqualTo(owner.snapshot().runId());
            repository.fail(
                    owner.snapshot().runId(),
                    owner.leaseToken(),
                    "DIRECTORY_TEST_ABORTED",
                    "The integration test closed the active run",
                    first.actor()
            );
        }
    }

    @Test
    void reclaimsExpiredLeaseAndFencesOldWorker() {
        try (RequestCorrelationContext.Scope ignored = correlation("m104-expired-context")) {
            DirectorySyncCommand first = command("m104-expired-a");
            DirectorySyncClaim expired = repository.claim(COMPANY_ID, first, LEASE);
            jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_run
                        SET lease_until = :expiredAt
                        WHERE id = :runId
                        """)
                .param("expiredAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
                .param("runId", expired.snapshot().runId())
                .update();

            DirectorySyncCommand replacementCommand = command("m104-expired-b");
            DirectorySyncClaim replacement = repository.claim(
                    COMPANY_ID,
                    replacementCommand,
                    LEASE
            );

            assertThat(replacement.executionOwner()).isTrue();
            assertThat(repository.find(expired.snapshot().runId()).status())
                    .isEqualTo(DirectorySyncRunStatus.FAILED);
            assertThatThrownBy(() -> repository.stageIdPage(
                    expired.snapshot().runId(),
                    expired.leaseToken(),
                    1,
                    1,
                    "cursor-secret",
                    List.of("member-a"),
                    LEASE
            )).isInstanceOf(DirectorySyncLeaseLostException.class);
            repository.fail(
                    replacement.snapshot().runId(),
                    replacement.leaseToken(),
                    "DIRECTORY_TEST_ABORTED",
                    "The integration test closed the replacement run",
                    replacementCommand.actor()
            );
        }
    }

    @Test
    void concurrentDifferentTriggersShareExactlyOneRunningOwner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<DirectorySyncClaim> first = executor.submit(() -> concurrentClaim(
                    "m104-concurrent-a",
                    ready,
                    start
            ));
            Future<DirectorySyncClaim> second = executor.submit(() -> concurrentClaim(
                    "m104-concurrent-b",
                    ready,
                    start
            ));
            ready.await();
            start.countDown();

            List<DirectorySyncClaim> claims = List.of(first.get(), second.get());
            assertThat(claims).extracting(claim -> claim.snapshot().runId())
                    .containsOnly(claims.getFirst().snapshot().runId());
            assertThat(claims).filteredOn(DirectorySyncClaim::executionOwner).hasSize(1);
            DirectorySyncClaim owner = claims.stream()
                    .filter(DirectorySyncClaim::executionOwner)
                    .findFirst()
                    .orElseThrow();
            try (RequestCorrelationContext.Scope ignored = correlation("m104-concurrent-close")) {
                repository.fail(
                        owner.snapshot().runId(),
                        owner.leaseToken(),
                        "DIRECTORY_TEST_ABORTED",
                        "The integration test closed the concurrent run",
                        EventActor.system("DIRECTORY_SYNC_TEST")
                );
            }
        }
    }

    @Test
    void successfulReconciliationMarksMissingMemberLeftAndReturnReusesIdentity() {
        WeComMemberProfile alice = profile("member-a", "Alice", "a");
        WeComMemberProfile bob = profile("member-b", "Bob", "b");
        executeProfiles("m105-baseline", List.of(alice, bob));
        UUID bobUserId = userId("member-b");
        var bobSession = sessionService.issueWebSession(bobUserId, "m107-directory");

        DirectorySyncRunSnapshot left = executeProfiles("m105-left", List.of(alice));

        assertThat(left.counts().left()).isOne();
        assertThat(memberState("member-b"))
                .isEqualTo("LEFT|LEFT|ENABLED|1|DIRECTORY_SNAPSHOT_MISSING");
        assertThatThrownBy(() -> sessionService.authenticate(bobSession.sessionCredential()))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(StandardErrorCode.ACCOUNT_DISABLED));
        assertThat(jdbcClient.sql("""
                        SELECT status || '|' || revoke_reason
                        FROM yumpoo.login_session
                        WHERE id = :sessionId
                        """)
                .param("sessionId", bobSession.session().id())
                .query(String.class)
                .single()).isEqualTo("REVOKED|EMPLOYMENT_LEFT");
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.outbox_event
                        WHERE event_type = 'identity.user_sessions_revoked'
                          AND event_version = 2
                          AND aggregate_id = :userId
                          AND payload_json ->> 'reasonCode' = 'EMPLOYMENT_LEFT'
                        """)
                .param("userId", bobUserId)
                .query(Integer.class)
                .single()).isOne();

        DirectorySyncRunSnapshot returned = executeProfiles(
                "m105-returned",
                List.of(alice, bob)
        );

        assertThat(returned.counts().returned()).isOne();
        assertThat(userId("member-b")).isEqualTo(bobUserId);
        assertThat(memberState("member-b"))
                .isEqualTo("ACTIVE|ACTIVE|ENABLED|2|DIRECTORY_SNAPSHOT_MISSING");
        assertThatThrownBy(() -> sessionService.authenticate(bobSession.sessionCredential()))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(StandardErrorCode.ACCOUNT_DISABLED));
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.outbox_event
                        WHERE event_type = 'identity.user_employment_left'
                          AND aggregate_id = :userId
                        """)
                .param("userId", bobUserId)
                .query(Integer.class)
                .single()).isOne();
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.outbox_event
                        WHERE event_type = 'identity.user_employment_returned'
                          AND aggregate_id = :userId
                        """)
                .param("userId", bobUserId)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void partialRunKeepsSuccessfulItemsButSuppressesMissingMemberReconciliation() {
        WeComMemberProfile alice = profile("member-a", "Alice", "a");
        WeComMemberProfile bob = profile("member-b", "Bob", "b");
        executeProfiles("m105-partial-baseline", List.of(alice, bob));

        try (RequestCorrelationContext.Scope ignored = correlation("m105-partial")) {
            DirectorySyncCommand command = command("m105-partial");
            DirectorySyncClaim claim = repository.claim(COMPANY_ID, command, LEASE);
            UUID runId = claim.snapshot().runId();
            UUID leaseToken = claim.leaseToken();
            WeComMemberProfile carol = profile("member-c", "Carol", "c");
            List<WeComMemberProfile> profiles = List.of(alice, carol);
            List<String> members = profiles.stream()
                    .map(WeComMemberProfile::externalUserId)
                    .toList();
            confirm(runId, leaseToken, members);
            profiles.forEach(profile -> repository.stageProfile(
                    runId,
                    leaseToken,
                    profile,
                    LEASE
            ));
            repository.beginApplying(runId, leaseToken, LEASE);
            itemApplyService.apply(runId, leaseToken, alice, command.actor(), LEASE);
            repository.markApplyFailed(runId, leaseToken, "member-c", LEASE);

            DirectorySyncRunSnapshot partial = repository.completePartial(
                    runId,
                    leaseToken,
                    command.actor()
            );

            assertThat(partial.status()).isEqualTo(DirectorySyncRunStatus.PARTIALLY_SUCCEEDED);
            assertThat(partial.counts().failed()).isOne();
            assertThat(partial.counts().left()).isZero();
            assertThat(memberState("member-b")).startsWith("ACTIVE|ACTIVE|");
            assertThat(count("directory_sync_staging_member")).isZero();
        }
    }

    private DirectorySyncRunSnapshot execute(
            String triggerKey,
            String displayName,
            DirectoryOptionalField email,
            String hashCharacter
    ) {
        return executeProfiles(triggerKey, List.of(new WeComMemberProfile(
                "member-a",
                displayName,
                email,
                DirectoryOptionalField.present("13800000000"),
                "研发部",
                new ProfileHash(hashCharacter.repeat(64))
        )));
    }

    private DirectorySyncRunSnapshot executeProfiles(
            String triggerKey,
            List<WeComMemberProfile> profiles
    ) {
        try (RequestCorrelationContext.Scope ignored = correlation(triggerKey)) {
            DirectorySyncCommand command = command(triggerKey);
            DirectorySyncClaim claim = repository.claim(COMPANY_ID, command, LEASE);
            UUID runId = claim.snapshot().runId();
            UUID leaseToken = claim.leaseToken();
            List<String> members = profiles.stream()
                    .map(WeComMemberProfile::externalUserId)
                    .toList();
            confirm(runId, leaseToken, members);
            profiles.forEach(profile -> repository.stageProfile(
                    runId,
                    leaseToken,
                    profile,
                    LEASE
            ));
            repository.beginApplying(runId, leaseToken, LEASE);
            profiles.forEach(profile -> itemApplyService.apply(
                    runId,
                    leaseToken,
                    profile,
                    command.actor(),
                    LEASE
            ));
            DirectorySyncRunSnapshot completed = repository.complete(
                runId,
                leaseToken,
                command.actor()
        );

            DirectorySyncClaim replay = repository.claim(COMPANY_ID, command, LEASE);
            assertThat(replay.executionOwner()).isFalse();
            assertThat(replay.snapshot()).isEqualTo(completed);
            return completed;
        }
    }

    private void confirm(UUID runId, UUID leaseToken, List<String> members) {
        repository.stageIdPage(runId, leaseToken, 1, 1, "", members, LEASE);
        repository.confirmScan(
                runId,
                leaseToken,
                new DirectoryScanResult(
                        members,
                        DirectoryScanResult.CursorTerminationMode.EXPLICIT_EMPTY,
                        1,
                        DirectoryCanonicalHash.strings("directory-members-v1", members),
                        DirectoryCanonicalHash.strings("directory-pages-v1", List.of(
                                DirectoryCanonicalHash.strings("directory-page-v1", members)
                        ))
                ),
                LEASE
        );
    }

    private static WeComMemberProfile profile(
            String externalUserId,
            String displayName,
            String hashCharacter
    ) {
        return new WeComMemberProfile(
                externalUserId,
                displayName,
                DirectoryOptionalField.present(displayName.toLowerCase() + "@example.test"),
                DirectoryOptionalField.present("13800000000"),
                "研发部",
                new ProfileHash(hashCharacter.repeat(64))
        );
    }

    private static DirectorySyncCommand command(String triggerKey) {
        return new DirectorySyncCommand(
                triggerKey,
                DirectorySyncTriggerType.SCHEDULED,
                EventActor.system("DIRECTORY_SYNC_TEST"),
                triggerKey
        );
    }

    private static RequestCorrelationContext.Scope correlation(String requestId) {
        return RequestCorrelationContext.open(RequestCorrelation.root(requestId));
    }

    private DirectorySyncClaim concurrentClaim(
            String triggerKey,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try (RequestCorrelationContext.Scope ignored = correlation(triggerKey)) {
            return repository.claim(COMPANY_ID, command(triggerKey), LEASE);
        }
    }

    private UUID singleUserId() {
        return jdbcClient.sql("SELECT id FROM yumpoo.identity_user")
                .query(UUID.class)
                .single();
    }

    private UUID userId(String externalUserId) {
        return jdbcClient.sql("""
                        SELECT user_id
                        FROM yumpoo.external_identity
                        WHERE external_user_id = :externalUserId
                        """)
                .param("externalUserId", externalUserId)
                .query(UUID.class)
                .single();
    }

    private String memberState(String externalUserId) {
        return jdbcClient.sql("""
                        SELECT identity_user.employment_status || '|'
                            || external_identity.provider_employment_status || '|'
                            || identity_user.account_status || '|'
                            || identity_user.authorization_version || '|'
                            || COALESCE(identity_user.left_reason, '')
                        FROM yumpoo.external_identity external_identity
                        JOIN yumpoo.identity_user identity_user
                          ON identity_user.id = external_identity.user_id
                        WHERE external_identity.external_user_id = :externalUserId
                        """)
                .param("externalUserId", externalUserId)
                .query(String.class)
                .single();
    }

    private int count(String table) {
        if (!List.of(
                "identity_user",
                "external_identity",
                "directory_sync_staging_member"
        ).contains(table)) {
            throw new IllegalArgumentException("Unsupported table");
        }
        return jdbcClient.sql("SELECT count(*) FROM yumpoo." + table)
                .query(Integer.class)
                .single();
    }
}
