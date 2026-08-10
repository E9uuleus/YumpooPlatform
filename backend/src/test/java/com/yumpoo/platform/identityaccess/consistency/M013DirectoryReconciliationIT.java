package com.yumpoo.platform.identityaccess.consistency;

import com.yumpoo.platform.identityaccess.testing.M013DirectoryProbeRepository;
import com.yumpoo.platform.identityaccess.testing.M013DirectoryReconciliationService;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({
        PostgreSqlTestContainerConfiguration.class,
        M013DirectoryProbeRepository.class,
        M013DirectoryReconciliationService.class
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(
        scripts = "/sql/m0-13-probe-schema.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
@Sql(
        scripts = "/sql/m0-13-probe-drop.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS
)
class M013DirectoryReconciliationIT {

    private static final String MEMBER_A = "a".repeat(64);
    private static final String MEMBER_B = "b".repeat(64);
    private static final String MEMBER_C = "c".repeat(64);

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private M013DirectoryProbeRepository repository;

    @Autowired
    private M013DirectoryReconciliationService reconciliationService;

    @BeforeEach
    void resetDirectoryProbe() {
        jdbcClient.sql("""
                        TRUNCATE TABLE
                            yumpoo.m013_probe_seen,
                            yumpoo.m013_probe_member,
                            yumpoo.m013_probe_run
                        """)
                .update();
    }

    @Test
    void completeSynchronizationPersistsRunSeenAndMembers() {
        M013DirectoryProbeRepository.RunRow run = reconciliationService.synchronize(
                Set.of(MEMBER_A, MEMBER_B),
                true,
                Set.of()
        );

        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.scanComplete()).isTrue();
        assertThat(run.discoveredCount()).isEqualTo(2);
        assertThat(run.createdCount()).isEqualTo(2);
        assertThat(run.unchangedCount()).isZero();
        assertThat(run.returnedCount()).isZero();
        assertThat(run.leftCount()).isZero();
        assertThat(run.failedCount()).isZero();
        assertThat(repository.seenCount(run.id())).isEqualTo(2);
        assertThat(repository.memberCount()).isEqualTo(2);

        assertThat(repository.findSeen(run.id(), MEMBER_A)).hasValueSatisfying(seen -> {
            assertThat(seen.result()).isEqualTo("CREATED");
            assertThat(seen.memberId()).isNotNull();
            assertThat(seen.errorCode()).isNull();
        });
        assertThat(repository.findMember(MEMBER_A)).hasValueSatisfying(member -> {
            assertThat(member.employmentStatus()).isEqualTo("ACTIVE");
            assertThat(member.firstSeenRunId()).isEqualTo(run.id());
            assertThat(member.lastSeenRunId()).isEqualTo(run.id());
        });
    }

    @Test
    void secondPageFailureStagesSeenButDoesNotReconcileOrMarkMissingMembersLeft() {
        M013DirectoryProbeRepository.RunRow baseline = reconciliationService.synchronize(
                Set.of(MEMBER_A, MEMBER_B),
                true,
                Set.of()
        );
        M013DirectoryProbeRepository.MemberRow originalA = member(MEMBER_A);
        M013DirectoryProbeRepository.MemberRow originalB = member(MEMBER_B);

        M013DirectoryProbeRepository.RunRow failed = reconciliationService.synchronize(
                Set.of(MEMBER_A),
                false,
                Set.of()
        );

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.scanComplete()).isFalse();
        assertThat(failed.discoveredCount()).isOne();
        assertThat(failed.createdCount()).isZero();
        assertThat(failed.unchangedCount()).isZero();
        assertThat(failed.returnedCount()).isZero();
        assertThat(failed.leftCount()).isZero();
        assertThat(failed.failedCount()).isZero();
        assertThat(repository.findSeen(failed.id(), MEMBER_A)).hasValueSatisfying(seen ->
                assertThat(seen.result()).isEqualTo("STAGED")
        );

        assertThat(member(MEMBER_A)).isEqualTo(originalA);
        assertThat(member(MEMBER_B)).isEqualTo(originalB);
        assertThat(originalA.lastSeenRunId()).isEqualTo(baseline.id());
        assertThat(originalB.lastSeenRunId()).isEqualTo(baseline.id());
        assertThat(repository.memberCount()).isEqualTo(2);
    }

    @Test
    void singleMemberFailureIsPartialAndSuppressesMissingMemberReconciliation() {
        M013DirectoryProbeRepository.RunRow baseline = reconciliationService.synchronize(
                Set.of(MEMBER_A, MEMBER_B),
                true,
                Set.of()
        );
        UUID memberBId = member(MEMBER_B).id();

        M013DirectoryProbeRepository.RunRow partial = reconciliationService.synchronize(
                Set.of(MEMBER_A, MEMBER_C),
                true,
                Set.of(MEMBER_C)
        );

        assertThat(partial.status()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(partial.scanComplete()).isTrue();
        assertThat(partial.discoveredCount()).isEqualTo(2);
        assertThat(partial.createdCount()).isZero();
        assertThat(partial.unchangedCount()).isOne();
        assertThat(partial.returnedCount()).isZero();
        assertThat(partial.leftCount()).isZero();
        assertThat(partial.failedCount()).isOne();
        assertThat(repository.findSeen(partial.id(), MEMBER_C)).hasValueSatisfying(seen -> {
            assertThat(seen.result()).isEqualTo("FAILED");
            assertThat(seen.memberId()).isNull();
            assertThat(seen.errorCode())
                    .isEqualTo(M013DirectoryReconciliationService.ITEM_FAILURE_CODE);
        });
        assertThat(repository.findMember(MEMBER_C)).isEmpty();
        assertThat(member(MEMBER_B).id()).isEqualTo(memberBId);
        assertThat(member(MEMBER_B).employmentStatus()).isEqualTo("ACTIVE");
        assertThat(member(MEMBER_B).lastSeenRunId()).isEqualTo(baseline.id());
        assertThat(repository.memberCount()).isEqualTo(2);
    }

    @Test
    void existingMemberWriteIsRolledBackBeforeTheRunIsMarkedPartial() {
        M013DirectoryProbeRepository.RunRow baseline = reconciliationService.synchronize(
                Set.of(MEMBER_A, MEMBER_B),
                true,
                Set.of()
        );
        M013DirectoryProbeRepository.MemberRow originalA = member(MEMBER_A);

        M013DirectoryProbeRepository.RunRow partial = reconciliationService.synchronize(
                Set.of(MEMBER_A, MEMBER_B),
                true,
                Set.of(MEMBER_A)
        );

        assertThat(partial.status()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(partial.createdCount()).isZero();
        assertThat(partial.unchangedCount()).isOne();
        assertThat(partial.leftCount()).isZero();
        assertThat(partial.failedCount()).isOne();
        assertThat(member(MEMBER_A)).isEqualTo(originalA);
        assertThat(member(MEMBER_A).lastSeenRunId()).isEqualTo(baseline.id());
        assertThat(member(MEMBER_B).lastSeenRunId()).isEqualTo(partial.id());
        assertThat(repository.findSeen(partial.id(), MEMBER_A)).hasValueSatisfying(seen -> {
            assertThat(seen.result()).isEqualTo("FAILED");
            assertThat(seen.memberId()).isNull();
            assertThat(seen.errorCode())
                    .isEqualTo(M013DirectoryReconciliationService.ITEM_FAILURE_CODE);
        });
    }

    @Test
    void rerunDoesNotDuplicateAndOmittedMemberReturnsWithTheSameUuid() {
        M013DirectoryProbeRepository.RunRow first = reconciliationService.synchronize(
                Set.of(MEMBER_A, MEMBER_B),
                true,
                Set.of()
        );
        M013DirectoryProbeRepository.MemberRow firstA = member(MEMBER_A);
        M013DirectoryProbeRepository.MemberRow firstB = member(MEMBER_B);
        UUID memberAId = firstA.id();
        UUID memberBId = firstB.id();
        assertThat(firstA.firstSeenRunId()).isEqualTo(first.id());
        assertThat(firstB.firstSeenRunId()).isEqualTo(first.id());

        M013DirectoryProbeRepository.RunRow replay = reconciliationService.synchronize(
                Set.of(MEMBER_A, MEMBER_B),
                true,
                Set.of()
        );

        assertThat(replay.status()).isEqualTo("SUCCEEDED");
        assertThat(replay.createdCount()).isZero();
        assertThat(replay.unchangedCount()).isEqualTo(2);
        assertThat(repository.memberCount()).isEqualTo(2);
        assertThat(member(MEMBER_A).id()).isEqualTo(memberAId);
        assertThat(member(MEMBER_B).id()).isEqualTo(memberBId);
        assertThat(member(MEMBER_A).firstSeenRunId()).isEqualTo(first.id());
        assertThat(member(MEMBER_B).firstSeenRunId()).isEqualTo(first.id());
        assertThat(member(MEMBER_A).lastSeenRunId()).isEqualTo(replay.id());
        assertThat(member(MEMBER_B).lastSeenRunId()).isEqualTo(replay.id());

        M013DirectoryProbeRepository.RunRow omitted = reconciliationService.synchronize(
                Set.of(MEMBER_A),
                true,
                Set.of()
        );

        assertThat(omitted.status()).isEqualTo("SUCCEEDED");
        assertThat(omitted.leftCount()).isOne();
        assertThat(member(MEMBER_B).id()).isEqualTo(memberBId);
        assertThat(member(MEMBER_B).employmentStatus()).isEqualTo("LEFT");

        M013DirectoryProbeRepository.RunRow restored = reconciliationService.synchronize(
                Set.of(MEMBER_A, MEMBER_B),
                true,
                Set.of()
        );

        assertThat(restored.status()).isEqualTo("SUCCEEDED");
        assertThat(restored.createdCount()).isZero();
        assertThat(restored.unchangedCount()).isOne();
        assertThat(restored.returnedCount()).isOne();
        assertThat(restored.leftCount()).isZero();
        assertThat(repository.memberCount()).isEqualTo(2);
        assertThat(member(MEMBER_B).id()).isEqualTo(memberBId);
        assertThat(member(MEMBER_B).employmentStatus()).isEqualTo("ACTIVE");
        assertThat(member(MEMBER_B).firstSeenRunId()).isEqualTo(first.id());
        assertThat(member(MEMBER_B).lastSeenRunId()).isEqualTo(restored.id());
    }

    @Test
    void successfulFinalizationRollsBackLeftReconciliationAsOneTransaction() {
        reconciliationService.synchronize(Set.of(MEMBER_A, MEMBER_B), true, Set.of());
        M013DirectoryProbeRepository.MemberRow originalB = member(MEMBER_B);
        UUID finalizingRunId = repository.startRun();
        repository.stageSeen(finalizingRunId, Set.of(MEMBER_A));
        repository.reconcileSeenMember(finalizingRunId, MEMBER_A);

        assertThatThrownBy(() -> repository.finishSuccessfulRunThenFail(finalizingRunId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced M0-13 successful finalization rollback");

        assertThat(member(MEMBER_B)).isEqualTo(originalB);
        assertThat(repository.findRun(finalizingRunId)).hasValueSatisfying(run -> {
            assertThat(run.status()).isEqualTo("RUNNING");
            assertThat(run.scanComplete()).isFalse();
            assertThat(run.leftCount()).isZero();
        });
    }

    @Test
    void successfulFinalizationRejectsStagedOrFailedSeenMembersBeforeLeftReconciliation() {
        reconciliationService.synchronize(Set.of(MEMBER_A, MEMBER_B), true, Set.of());
        M013DirectoryProbeRepository.MemberRow originalB = member(MEMBER_B);

        UUID stagedRunId = repository.startRun();
        repository.stageSeen(stagedRunId, Set.of(MEMBER_A));
        assertThatThrownBy(() -> repository.finishSuccessfulRun(stagedRunId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "M0-13 successful finalization requires every seen member to succeed"
                );
        assertThatThrownBy(() -> repository.finishRun(
                stagedRunId,
                "SUCCEEDED",
                true,
                0
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid non-success M0-13 run status");

        UUID failedRunId = repository.startRun();
        repository.stageSeen(failedRunId, Set.of(MEMBER_A));
        repository.markSeenFailed(
                failedRunId,
                MEMBER_A,
                M013DirectoryReconciliationService.ITEM_FAILURE_CODE
        );
        assertThatThrownBy(() -> repository.finishSuccessfulRun(failedRunId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "M0-13 successful finalization requires every seen member to succeed"
                );

        assertThat(member(MEMBER_B)).isEqualTo(originalB);
        assertThat(repository.findRun(stagedRunId)).hasValueSatisfying(run ->
                assertThat(run.status()).isEqualTo("RUNNING")
        );
        assertThat(repository.findRun(failedRunId)).hasValueSatisfying(run ->
                assertThat(run.status()).isEqualTo("RUNNING")
        );
    }

    private M013DirectoryProbeRepository.MemberRow member(String memberFingerprint) {
        return repository.findMember(memberFingerprint).orElseThrow();
    }
}
