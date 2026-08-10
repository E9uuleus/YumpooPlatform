package com.yumpoo.platform.identityaccess.testing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 仅供 M0-13 真实 PostgreSQL 隔离验收使用，不进入生产制品或 Flyway。
 */
public class M013DirectoryProbeRepository {

    private static final Set<String> NON_SUCCESS_TERMINAL_RUN_STATUSES = Set.of(
            "PARTIALLY_SUCCEEDED",
            "FAILED"
    );

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public M013DirectoryProbeRepository(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public UUID startRun() {
        UUID runId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.m013_probe_run (
                            id, status, scan_complete, started_at
                        ) VALUES (
                            :id, 'RUNNING', false, :startedAt
                        )
                        """)
                .param("id", runId)
                .param("startedAt", now())
                .update();
        return runId;
    }

    @Transactional
    public void stageSeen(UUID runId, Set<String> memberFingerprints) {
        for (String memberFingerprint : memberFingerprints.stream().sorted().toList()) {
            jdbcClient.sql("""
                            INSERT INTO yumpoo.m013_probe_seen (
                                run_id, member_fingerprint, result
                            ) VALUES (
                                :runId, :memberFingerprint, 'STAGED'
                            )
                            """)
                    .param("runId", runId)
                    .param("memberFingerprint", memberFingerprint)
                    .update();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String reconcileSeenMember(UUID runId, String memberFingerprint) {
        return reconcileSeenMemberInCurrentTransaction(runId, memberFingerprint);
    }

    /**
     * 受控故障探针：先执行真实成员写入，再在同一 REQUIRES_NEW 事务中抛错以验证回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileSeenMemberThenFail(UUID runId, String memberFingerprint) {
        reconcileSeenMemberInCurrentTransaction(runId, memberFingerprint);
        throw new InjectedItemReconciliationFailure();
    }

    private String reconcileSeenMemberInCurrentTransaction(
            UUID runId,
            String memberFingerprint
    ) {
        Optional<ExistingMember> existing = jdbcClient.sql("""
                        SELECT id, employment_status
                        FROM yumpoo.m013_probe_member
                        WHERE member_fingerprint = :memberFingerprint
                        FOR UPDATE
                        """)
                .param("memberFingerprint", memberFingerprint)
                .query((resultSet, rowNumber) -> new ExistingMember(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("employment_status")
                ))
                .optional();

        UUID memberId;
        String result;
        OffsetDateTime reconciledAt = now();
        if (existing.isEmpty()) {
            memberId = UUID.randomUUID();
            result = "CREATED";
            jdbcClient.sql("""
                            INSERT INTO yumpoo.m013_probe_member (
                                id, member_fingerprint, employment_status,
                                first_seen_run_id, last_seen_run_id, created_at, updated_at
                            ) VALUES (
                                :id, :memberFingerprint, 'ACTIVE',
                                :runId, :runId, :reconciledAt, :reconciledAt
                            )
                            """)
                    .param("id", memberId)
                    .param("memberFingerprint", memberFingerprint)
                    .param("runId", runId)
                    .param("reconciledAt", reconciledAt)
                    .update();
        } else {
            memberId = existing.orElseThrow().id();
            result = "LEFT".equals(existing.orElseThrow().employmentStatus())
                    ? "RETURNED"
                    : "UNCHANGED";
            jdbcClient.sql("""
                            UPDATE yumpoo.m013_probe_member
                            SET employment_status = 'ACTIVE',
                                last_seen_run_id = :runId,
                                updated_at = :reconciledAt
                            WHERE id = :id
                            """)
                    .param("runId", runId)
                    .param("reconciledAt", reconciledAt)
                    .param("id", memberId)
                    .update();
        }

        updateSeenOutcome(runId, memberFingerprint, result, memberId, null);
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSeenFailed(UUID runId, String memberFingerprint, String errorCode) {
        updateSeenOutcome(runId, memberFingerprint, "FAILED", null, errorCode);
    }

    @Transactional
    public RunRow finishSuccessfulRun(UUID runId) {
        SeenCounts counts = seenCounts(runId);
        int successfulCount = counts.created() + counts.unchanged() + counts.returned();
        if (counts.failed() != 0 || successfulCount != counts.discovered()) {
            throw new IllegalStateException(
                    "M0-13 successful finalization requires every seen member to succeed"
            );
        }
        int leftCount = markMissingMembersLeft(runId);
        return finishRunState(runId, "SUCCEEDED", true, leftCount);
    }

    /** 仅用于证明 LEFT 对账会随成功收尾事务一并回滚。 */
    @Transactional
    public void finishSuccessfulRunThenFail(UUID runId) {
        markMissingMembersLeft(runId);
        throw new IllegalStateException("forced M0-13 successful finalization rollback");
    }

    private int markMissingMembersLeft(UUID runId) {
        return jdbcClient.sql("""
                        UPDATE yumpoo.m013_probe_member member
                        SET employment_status = 'LEFT',
                            updated_at = :reconciledAt
                        WHERE member.employment_status = 'ACTIVE'
                          AND NOT EXISTS (
                              SELECT 1
                              FROM yumpoo.m013_probe_seen seen
                              WHERE seen.run_id = :runId
                                AND seen.member_fingerprint = member.member_fingerprint
                          )
                        """)
                .param("runId", runId)
                .param("reconciledAt", now())
                .update();
    }

    @Transactional
    public RunRow finishRun(
            UUID runId,
            String status,
            boolean scanComplete,
            int leftCount
    ) {
        if (!NON_SUCCESS_TERMINAL_RUN_STATUSES.contains(status)) {
            throw new IllegalArgumentException("invalid non-success M0-13 run status");
        }
        if (leftCount < 0) {
            throw new IllegalArgumentException("leftCount must not be negative");
        }
        return finishRunState(runId, status, scanComplete, leftCount);
    }

    private RunRow finishRunState(
            UUID runId,
            String status,
            boolean scanComplete,
            int leftCount
    ) {
        SeenCounts counts = seenCounts(runId);
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.m013_probe_run
                        SET status = :status,
                            scan_complete = :scanComplete,
                            discovered_count = :discoveredCount,
                            created_count = :createdCount,
                            unchanged_count = :unchangedCount,
                            returned_count = :returnedCount,
                            left_count = :leftCount,
                            failed_count = :failedCount,
                            finished_at = :finishedAt
                        WHERE id = :runId AND status = 'RUNNING'
                        """)
                .param("status", status)
                .param("scanComplete", scanComplete)
                .param("discoveredCount", counts.discovered())
                .param("createdCount", counts.created())
                .param("unchangedCount", counts.unchanged())
                .param("returnedCount", counts.returned())
                .param("leftCount", leftCount)
                .param("failedCount", counts.failed())
                .param("finishedAt", now())
                .param("runId", runId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("M0-13 run is not active");
        }
        return findRun(runId).orElseThrow();
    }

    public Optional<RunRow> findRun(UUID runId) {
        return jdbcClient.sql("""
                        SELECT id, status, scan_complete, discovered_count,
                               created_count, unchanged_count, returned_count,
                               left_count, failed_count
                        FROM yumpoo.m013_probe_run
                        WHERE id = :runId
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new RunRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getBoolean("scan_complete"),
                        resultSet.getInt("discovered_count"),
                        resultSet.getInt("created_count"),
                        resultSet.getInt("unchanged_count"),
                        resultSet.getInt("returned_count"),
                        resultSet.getInt("left_count"),
                        resultSet.getInt("failed_count")
                ))
                .optional();
    }

    public Optional<MemberRow> findMember(String memberFingerprint) {
        return jdbcClient.sql("""
                        SELECT id, member_fingerprint, employment_status,
                               first_seen_run_id, last_seen_run_id
                        FROM yumpoo.m013_probe_member
                        WHERE member_fingerprint = :memberFingerprint
                        """)
                .param("memberFingerprint", memberFingerprint)
                .query((resultSet, rowNumber) -> new MemberRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("member_fingerprint"),
                        resultSet.getString("employment_status"),
                        resultSet.getObject("first_seen_run_id", UUID.class),
                        resultSet.getObject("last_seen_run_id", UUID.class)
                ))
                .optional();
    }

    public Optional<SeenRow> findSeen(UUID runId, String memberFingerprint) {
        return jdbcClient.sql("""
                        SELECT run_id, member_fingerprint, result, member_id, error_code
                        FROM yumpoo.m013_probe_seen
                        WHERE run_id = :runId AND member_fingerprint = :memberFingerprint
                        """)
                .param("runId", runId)
                .param("memberFingerprint", memberFingerprint)
                .query((resultSet, rowNumber) -> new SeenRow(
                        resultSet.getObject("run_id", UUID.class),
                        resultSet.getString("member_fingerprint"),
                        resultSet.getString("result"),
                        resultSet.getObject("member_id", UUID.class),
                        resultSet.getString("error_code")
                ))
                .optional();
    }

    public long memberCount() {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.m013_probe_member")
                .query(Long.class)
                .single();
    }

    public long seenCount(UUID runId) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.m013_probe_seen
                        WHERE run_id = :runId
                        """)
                .param("runId", runId)
                .query(Long.class)
                .single();
    }

    private SeenCounts seenCounts(UUID runId) {
        return jdbcClient.sql("""
                        SELECT count(*) AS discovered,
                               count(*) FILTER (WHERE result = 'CREATED') AS created,
                               count(*) FILTER (WHERE result = 'UNCHANGED') AS unchanged,
                               count(*) FILTER (WHERE result = 'RETURNED') AS returned,
                               count(*) FILTER (WHERE result = 'FAILED') AS failed
                        FROM yumpoo.m013_probe_seen
                        WHERE run_id = :runId
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new SeenCounts(
                        resultSet.getInt("discovered"),
                        resultSet.getInt("created"),
                        resultSet.getInt("unchanged"),
                        resultSet.getInt("returned"),
                        resultSet.getInt("failed")
                ))
                .single();
    }

    private void updateSeenOutcome(
            UUID runId,
            String memberFingerprint,
            String result,
            UUID memberId,
            String errorCode
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.m013_probe_seen
                        SET result = :result,
                            member_id = :memberId,
                            error_code = :errorCode
                        WHERE run_id = :runId
                          AND member_fingerprint = :memberFingerprint
                          AND result = 'STAGED'
                        """)
                .param("result", result)
                .param("memberId", memberId)
                .param("errorCode", errorCode)
                .param("runId", runId)
                .param("memberFingerprint", memberFingerprint)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("M0-13 seen member is not staged");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record ExistingMember(UUID id, String employmentStatus) {
    }

    private record SeenCounts(
            int discovered,
            int created,
            int unchanged,
            int returned,
            int failed
    ) {
    }

    public record RunRow(
            UUID id,
            String status,
            boolean scanComplete,
            int discoveredCount,
            int createdCount,
            int unchangedCount,
            int returnedCount,
            int leftCount,
            int failedCount
    ) {
    }

    public record MemberRow(
            UUID id,
            String memberFingerprint,
            String employmentStatus,
            UUID firstSeenRunId,
            UUID lastSeenRunId
    ) {
    }

    public record SeenRow(
            UUID runId,
            String memberFingerprint,
            String result,
            UUID memberId,
            String errorCode
    ) {
    }

    public static final class InjectedItemReconciliationFailure extends RuntimeException {

        private InjectedItemReconciliationFailure() {
            super("forced M0-13 item reconciliation rollback");
        }
    }
}
