package com.yumpoo.platform.identityaccess.infrastructure.identity;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryCanonicalHash;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryOptionalField;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryScanResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncClaim;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncCommand;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncCounts;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncException;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncItemResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncLeaseLostException;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRepository;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunPhase;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunSnapshot;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunStatus;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncTriggerType;
import com.yumpoo.platform.identityaccess.application.directory.WeComMemberProfile;
import com.yumpoo.platform.identityaccess.domain.identity.ProfileHash;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDirectorySyncRepository implements DirectorySyncRepository {

    private static final String SELECT_SNAPSHOT = """
            SELECT
                id, company_id, trigger_type, phase, status,
                cursor_termination_mode, page_count, scan_complete,
                discovered_count, staged_count, created_count, updated_count,
                unchanged_count, left_count, returned_count, failed_count,
                not_applied_count, error_code, request_id, row_version,
                started_at, finished_at
            FROM yumpoo.directory_sync_run
            WHERE id = :runId
            """;

    private final JdbcClient jdbcClient;
    private final TransactionalEventPort eventPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcDirectorySyncRepository(
            JdbcClient jdbcClient,
            TransactionalEventPort eventPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.eventPort = Objects.requireNonNull(eventPort, "eventPort must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    @Transactional
    public DirectorySyncClaim claim(
            UUID companyId,
            DirectorySyncCommand command,
            Duration leaseDuration
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        requireLeaseDuration(leaseDuration);
        String triggerHash = DirectoryCanonicalHash.strings(
                "directory-trigger-v1",
                List.of(command.triggerKey())
        );

        Optional<DirectorySyncRunSnapshot> replay = findByTrigger(companyId, triggerHash);
        if (replay.isPresent()) {
            return new DirectorySyncClaim(replay.orElseThrow(), null, false);
        }

        Instant now = clock.instant();
        Optional<ActiveRun> active = activeForUpdate(companyId);
        if (active.isPresent()) {
            ActiveRun current = active.orElseThrow();
            if (current.leaseUntil().isAfter(now)) {
                return new DirectorySyncClaim(find(current.runId()), null, false);
            }
            DirectorySyncRunSnapshot expired = finishFailure(
                    current.runId(),
                    current.leaseToken(),
                    "DIRECTORY_SYNC_LEASE_EXPIRED",
                    "The previous directory synchronization lease expired",
                    EventActor.system("DIRECTORY_SYNC_LEASE_REAPER"),
                    now
            );
            publish("identity.directory_sync_failed", expired, EventActor.system(
                    "DIRECTORY_SYNC_LEASE_REAPER"
            ));
        }

        UUID runId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        OffsetDateTime databaseNow = databaseTime(now);
        OffsetDateTime leaseUntil = databaseTime(now.plus(leaseDuration));
        UUID inserted = jdbcClient.sql("""
                        INSERT INTO yumpoo.directory_sync_run (
                            id, company_id, trigger_type, triggered_by_user_id,
                            trigger_key_hash, phase, status, lease_token, lease_until,
                            request_id, row_version, started_at, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, :triggerType, :triggeredBy,
                            :triggerHash, 'COLLECTING_IDS', 'RUNNING', :leaseToken, :leaseUntil,
                            :requestId, 0, :now, :now, :now
                        )
                        ON CONFLICT DO NOTHING
                        RETURNING id
                        """)
                .param("id", runId)
                .param("companyId", companyId)
                .param("triggerType", command.triggerType().name())
                .param("triggeredBy", command.actor().userId())
                .param("triggerHash", triggerHash)
                .param("leaseToken", leaseToken)
                .param("leaseUntil", leaseUntil)
                .param("requestId", command.requestId())
                .param("now", databaseNow)
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (inserted == null) {
            replay = findByTrigger(companyId, triggerHash);
            if (replay.isPresent()) {
                return new DirectorySyncClaim(replay.orElseThrow(), null, false);
            }
            ActiveRun concurrent = activeForUpdate(companyId).orElseThrow(
                    () -> new IllegalStateException("Directory sync claim conflict had no owner")
            );
            return new DirectorySyncClaim(find(concurrent.runId()), null, false);
        }

        DirectorySyncRunSnapshot started = find(runId);
        publish("identity.directory_sync_started", started, command.actor());
        return new DirectorySyncClaim(started, leaseToken, true);
    }

    @Override
    @Transactional
    public void stageIdPage(
            UUID runId,
            UUID leaseToken,
            int pass,
            int pageNumber,
            String nextCursor,
            List<String> externalUserIds,
            Duration leaseDuration
    ) {
        if (pass != 1 && pass != 2) {
            throw new IllegalArgumentException("pass must be 1 or 2");
        }
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
        Instant now = clock.instant();
        OffsetDateTime databaseNow = databaseTime(now);
        for (String externalUserId : externalUserIds) {
            jdbcClient.sql("""
                            INSERT INTO yumpoo.directory_sync_staging_member (
                                run_id, external_user_id, first_scan_seen, second_scan_seen,
                                created_at, updated_at
                            ) VALUES (
                                :runId, :externalUserId, :firstSeen, :secondSeen, :now, :now
                            )
                            ON CONFLICT (run_id, external_user_id) DO UPDATE
                            SET first_scan_seen = yumpoo.directory_sync_staging_member.first_scan_seen
                                    OR EXCLUDED.first_scan_seen,
                                second_scan_seen = yumpoo.directory_sync_staging_member.second_scan_seen
                                    OR EXCLUDED.second_scan_seen,
                                updated_at = EXCLUDED.updated_at
                            """)
                    .param("runId", runId)
                    .param("externalUserId", externalUserId)
                    .param("firstSeen", pass == 1)
                    .param("secondSeen", pass == 2)
                    .param("now", databaseNow)
                    .update();
        }
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_run
                        SET provider_cursor = :providerCursor,
                            page_count = :pageCount,
                            discovered_count = (
                                SELECT count(*)
                                FROM yumpoo.directory_sync_staging_member staging
                                WHERE staging.run_id = :runId
                            ),
                            lease_until = :leaseUntil,
                            updated_at = :now,
                            row_version = row_version + 1
                        WHERE id = :runId
                          AND status = 'RUNNING'
                          AND lease_token = :leaseToken
                          AND lease_until >= :now
                        """)
                .param("providerCursor", normalizeCursor(nextCursor))
                .param("pageCount", pageNumber)
                .param("leaseUntil", databaseTime(now.plus(leaseDuration)))
                .param("now", databaseNow)
                .param("runId", runId)
                .param("leaseToken", leaseToken)
                .update();
        requireLease(updated);
    }

    @Override
    @Transactional
    public void confirmScan(
            UUID runId,
            UUID leaseToken,
            DirectoryScanResult result,
            Duration leaseDuration
    ) {
        Objects.requireNonNull(result, "result must not be null");
        if (result.terminationMode() == DirectoryScanResult.CursorTerminationMode.OMITTED_CONFIRMED) {
            Integer unconfirmed = jdbcClient.sql("""
                            SELECT count(*)
                            FROM yumpoo.directory_sync_staging_member
                            WHERE run_id = :runId
                              AND NOT (first_scan_seen AND second_scan_seen)
                            """)
                    .param("runId", runId)
                    .query(Integer.class)
                    .single();
            if (unconfirmed != 0) {
                throw new DirectorySyncException(
                        "DIRECTORY_OMITTED_CURSOR_MISMATCH",
                        "Persistent staging did not confirm both omitted-cursor scans"
                );
            }
        }
        Instant now = clock.instant();
        OffsetDateTime databaseNow = databaseTime(now);
        jdbcClient.sql("""
                        INSERT INTO yumpoo.directory_sync_item (
                            run_id, external_user_id, action, result, created_at, updated_at
                        )
                        SELECT run_id, external_user_id, 'PROVISION', 'PENDING', :now, :now
                        FROM yumpoo.directory_sync_staging_member
                        WHERE run_id = :runId
                        ON CONFLICT (run_id, external_user_id) DO NOTHING
                        """)
                .param("runId", runId)
                .param("now", databaseNow)
                .update();
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_run
                        SET phase = 'COLLECTING_PROFILES',
                            scan_complete = true,
                            provider_cursor = NULL,
                            cursor_termination_mode = :terminationMode,
                            page_count = :pageCount,
                            member_set_hash = :memberSetHash,
                            page_trajectory_hash = :pageTrajectoryHash,
                            discovered_count = :discoveredCount,
                            lease_until = :leaseUntil,
                            updated_at = :now,
                            row_version = row_version + 1
                        WHERE id = :runId
                          AND status = 'RUNNING'
                          AND lease_token = :leaseToken
                          AND lease_until >= :now
                        """)
                .param("terminationMode", result.terminationMode().name())
                .param("pageCount", result.pageCount())
                .param("memberSetHash", result.memberSetHash())
                .param("pageTrajectoryHash", result.pageTrajectoryHash())
                .param("discoveredCount", result.externalUserIds().size())
                .param("leaseUntil", databaseTime(now.plus(leaseDuration)))
                .param("now", databaseNow)
                .param("runId", runId)
                .param("leaseToken", leaseToken)
                .update();
        requireLease(updated);
    }

    @Override
    @Transactional
    public void stageProfile(
            UUID runId,
            UUID leaseToken,
            WeComMemberProfile profile,
            Duration leaseDuration
    ) {
        Instant now = clock.instant();
        OffsetDateTime databaseNow = databaseTime(now);
        int staged = jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_staging_member
                        SET display_name = :displayName,
                            email_state = :emailState,
                            email = :email,
                            mobile_state = :mobileState,
                            mobile = :mobile,
                            department_summary = :departmentSummary,
                            profile_hash = :profileHash,
                            updated_at = :now
                        WHERE run_id = :runId
                          AND external_user_id = :externalUserId
                        """)
                .param("displayName", profile.displayName())
                .param("emailState", profile.email().state().name())
                .param("email", profile.email().value())
                .param("mobileState", profile.mobile().state().name())
                .param("mobile", profile.mobile().value())
                .param("departmentSummary", profile.departmentSummary())
                .param("profileHash", profile.rawProfileHash().value())
                .param("now", databaseNow)
                .param("runId", runId)
                .param("externalUserId", profile.externalUserId())
                .update();
        if (staged != 1) {
            throw new DirectorySyncException(
                    "DIRECTORY_PROFILE_NOT_STAGED",
                    "A provider profile did not match the confirmed directory snapshot"
            );
        }
        jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_item
                        SET profile_hash = :profileHash, updated_at = :now
                        WHERE run_id = :runId AND external_user_id = :externalUserId
                        """)
                .param("profileHash", profile.rawProfileHash().value())
                .param("now", databaseNow)
                .param("runId", runId)
                .param("externalUserId", profile.externalUserId())
                .update();
        int renewed = renewRun(runId, leaseToken, leaseDuration, now, null);
        requireLease(renewed);
    }

    @Override
    @Transactional
    public void beginApplying(UUID runId, UUID leaseToken, Duration leaseDuration) {
        Instant now = clock.instant();
        Integer incomplete = jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.directory_sync_staging_member
                        WHERE run_id = :runId AND profile_hash IS NULL
                        """)
                .param("runId", runId)
                .query(Integer.class)
                .single();
        if (incomplete != 0) {
            throw new DirectorySyncException(
                    "DIRECTORY_PROFILE_SET_INCOMPLETE",
                    "Not every confirmed member had a complete staged profile"
            );
        }
        int renewed = renewRun(
                runId,
                leaseToken,
                leaseDuration,
                now,
                DirectorySyncRunPhase.APPLYING
        );
        requireLease(renewed);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WeComMemberProfile> stagedProfiles(UUID runId, UUID leaseToken) {
        assertLease(runId, leaseToken);
        return jdbcClient.sql("""
                        SELECT external_user_id, display_name,
                               email_state, email, mobile_state, mobile,
                               department_summary, profile_hash
                        FROM yumpoo.directory_sync_staging_member
                        WHERE run_id = :runId AND profile_hash IS NOT NULL
                        ORDER BY external_user_id
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new WeComMemberProfile(
                        resultSet.getString("external_user_id"),
                        resultSet.getString("display_name"),
                        field(resultSet, "email_state", "email"),
                        field(resultSet, "mobile_state", "mobile"),
                        resultSet.getString("department_summary"),
                        new ProfileHash(resultSet.getString("profile_hash"))
                ))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markApplied(
            UUID runId,
            UUID leaseToken,
            WeComMemberProfile profile,
            DirectoryMemberProvisioningResult result,
            Duration leaseDuration
    ) {
        DirectorySyncItemResult outcome = result.created()
                ? DirectorySyncItemResult.CREATED
                : result.profileChanged()
                        ? DirectorySyncItemResult.UPDATED
                        : DirectorySyncItemResult.UNCHANGED;
        Instant now = clock.instant();
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_item
                        SET user_id = :userId,
                            result = :result,
                            error_code = NULL,
                            updated_at = :now
                        WHERE run_id = :runId
                          AND external_user_id = :externalUserId
                          AND result = 'PENDING'
                        """)
                .param("userId", result.userId())
                .param("result", outcome.name())
                .param("now", databaseTime(now))
                .param("runId", runId)
                .param("externalUserId", profile.externalUserId())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Directory sync item outcome was not pending");
        }
        requireLease(renewRun(runId, leaseToken, leaseDuration, now, null));
    }

    @Override
    @Transactional
    public DirectorySyncRunSnapshot failDuringApply(
            UUID runId,
            UUID leaseToken,
            String externalUserId,
            EventActor actor
    ) {
        Instant now = clock.instant();
        jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_item
                        SET result = 'FAILED', error_code = 'DIRECTORY_APPLY_FAILED', updated_at = :now
                        WHERE run_id = :runId
                          AND external_user_id = :externalUserId
                          AND result = 'PENDING'
                        """)
                .param("now", databaseTime(now))
                .param("runId", runId)
                .param("externalUserId", externalUserId)
                .update();
        markPendingNotApplied(runId, now);
        DirectorySyncRunSnapshot failed = finishFailure(
                runId,
                leaseToken,
                "DIRECTORY_APPLY_FAILED",
                "A member write failed and the remaining members were not applied",
                actor,
                now
        );
        publish("identity.directory_sync_failed", failed, actor);
        return failed;
    }

    @Override
    @Transactional
    public DirectorySyncRunSnapshot fail(
            UUID runId,
            UUID leaseToken,
            String errorCode,
            String safeSummary,
            EventActor actor
    ) {
        Instant now = clock.instant();
        markPendingNotApplied(runId, now);
        DirectorySyncRunSnapshot failed = finishFailure(
                runId,
                leaseToken,
                errorCode,
                safeSummary,
                actor,
                now
        );
        publish("identity.directory_sync_failed", failed, actor);
        return failed;
    }

    @Override
    @Transactional
    public DirectorySyncRunSnapshot complete(UUID runId, UUID leaseToken, EventActor actor) {
        Instant now = clock.instant();
        assertLease(runId, leaseToken);
        ItemCounts counts = itemCounts(runId);
        DirectorySyncRunSnapshot current = find(runId);
        int processed = counts.created + counts.updated + counts.unchanged;
        if (!current.scanComplete()
                || counts.failed != 0
                || counts.notApplied != 0
                || processed != current.counts().discovered()) {
            throw new DirectorySyncException(
                    "DIRECTORY_FINALIZATION_INCOMPLETE",
                    "The directory item outcomes did not match the confirmed snapshot"
            );
        }
        deleteStaging(runId);
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_run
                        SET phase = 'COMPLETED', status = 'SUCCEEDED',
                            lease_token = NULL, lease_until = NULL, provider_cursor = NULL,
                            staged_count = :stagedCount,
                            created_count = :createdCount,
                            updated_count = :updatedCount,
                            unchanged_count = :unchangedCount,
                            failed_count = 0,
                            not_applied_count = 0,
                            finished_at = :now,
                            updated_at = :now,
                            row_version = row_version + 1
                        WHERE id = :runId
                          AND status = 'RUNNING'
                          AND lease_token = :leaseToken
                          AND lease_until >= :now
                        """)
                .param("stagedCount", current.counts().discovered())
                .param("createdCount", counts.created)
                .param("updatedCount", counts.updated)
                .param("unchangedCount", counts.unchanged)
                .param("now", databaseTime(now))
                .param("runId", runId)
                .param("leaseToken", leaseToken)
                .update();
        requireLease(updated);
        DirectorySyncRunSnapshot completed = find(runId);
        publish("identity.directory_sync_completed", completed, actor);
        return completed;
    }

    @Override
    @Transactional(readOnly = true)
    public DirectorySyncRunSnapshot find(UUID runId) {
        return jdbcClient.sql(SELECT_SNAPSHOT)
                .param("runId", runId)
                .query(JdbcDirectorySyncRepository::mapSnapshot)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Directory sync run was not found"));
    }

    private Optional<DirectorySyncRunSnapshot> findByTrigger(UUID companyId, String triggerHash) {
        return jdbcClient.sql(SELECT_SNAPSHOT.replace(
                        "WHERE id = :runId",
                        "WHERE company_id = :companyId AND trigger_key_hash = :triggerHash"
                ))
                .param("companyId", companyId)
                .param("triggerHash", triggerHash)
                .query(JdbcDirectorySyncRepository::mapSnapshot)
                .optional();
    }

    private Optional<ActiveRun> activeForUpdate(UUID companyId) {
        return jdbcClient.sql("""
                        SELECT id, lease_token, lease_until
                        FROM yumpoo.directory_sync_run
                        WHERE company_id = :companyId AND status = 'RUNNING'
                        FOR UPDATE
                        """)
                .param("companyId", companyId)
                .query((resultSet, rowNumber) -> new ActiveRun(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("lease_token", UUID.class),
                        resultSet.getTimestamp("lease_until").toInstant()
                ))
                .optional();
    }

    private int renewRun(
            UUID runId,
            UUID leaseToken,
            Duration leaseDuration,
            Instant now,
            DirectorySyncRunPhase phase
    ) {
        requireLeaseDuration(leaseDuration);
        return jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_run
                        SET phase = COALESCE(CAST(:phase AS varchar), phase),
                            staged_count = (
                                SELECT count(*)
                                FROM yumpoo.directory_sync_staging_member staging
                                WHERE staging.run_id = :runId AND staging.profile_hash IS NOT NULL
                            ),
                            lease_until = :leaseUntil,
                            updated_at = :now,
                            row_version = row_version + 1
                        WHERE id = :runId
                          AND status = 'RUNNING'
                          AND lease_token = :leaseToken
                          AND lease_until >= :now
                        """)
                .param("phase", phase == null ? null : phase.name())
                .param("leaseUntil", databaseTime(now.plus(leaseDuration)))
                .param("now", databaseTime(now))
                .param("runId", runId)
                .param("leaseToken", leaseToken)
                .update();
    }

    private void assertLease(UUID runId, UUID leaseToken) {
        Integer count = jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.directory_sync_run
                        WHERE id = :runId
                          AND status = 'RUNNING'
                          AND lease_token = :leaseToken
                          AND lease_until >= :now
                        """)
                .param("runId", runId)
                .param("leaseToken", leaseToken)
                .param("now", databaseTime(clock.instant()))
                .query(Integer.class)
                .single();
        requireLease(count);
    }

    private DirectorySyncRunSnapshot finishFailure(
            UUID runId,
            UUID leaseToken,
            String errorCode,
            String safeSummary,
            EventActor actor,
            Instant now
    ) {
        Objects.requireNonNull(actor, "actor must not be null");
        ItemCounts counts = itemCounts(runId);
        deleteStaging(runId);
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_run
                        SET phase = 'COMPLETED', status = 'FAILED',
                            lease_token = NULL, lease_until = NULL, provider_cursor = NULL,
                            created_count = :createdCount,
                            updated_count = :updatedCount,
                            unchanged_count = :unchangedCount,
                            failed_count = :failedCount,
                            not_applied_count = :notAppliedCount,
                            error_code = :errorCode,
                            error_summary = :errorSummary,
                            finished_at = :now,
                            updated_at = :now,
                            row_version = row_version + 1
                        WHERE id = :runId
                          AND status = 'RUNNING'
                          AND lease_token = :leaseToken
                        """)
                .param("createdCount", counts.created)
                .param("updatedCount", counts.updated)
                .param("unchangedCount", counts.unchanged)
                .param("failedCount", counts.failed)
                .param("notAppliedCount", counts.notApplied)
                .param("errorCode", errorCode)
                .param("errorSummary", safeSummary)
                .param("now", databaseTime(now))
                .param("runId", runId)
                .param("leaseToken", leaseToken)
                .update();
        requireLease(updated);
        return find(runId);
    }

    private void markPendingNotApplied(UUID runId, Instant now) {
        jdbcClient.sql("""
                        UPDATE yumpoo.directory_sync_item
                        SET result = 'NOT_APPLIED', error_code = 'DIRECTORY_RUN_ABORTED', updated_at = :now
                        WHERE run_id = :runId AND result = 'PENDING'
                        """)
                .param("now", databaseTime(now))
                .param("runId", runId)
                .update();
    }

    private void deleteStaging(UUID runId) {
        jdbcClient.sql("DELETE FROM yumpoo.directory_sync_staging_member WHERE run_id = :runId")
                .param("runId", runId)
                .update();
    }

    private ItemCounts itemCounts(UUID runId) {
        return jdbcClient.sql("""
                        SELECT
                            count(*) FILTER (WHERE result = 'CREATED') AS created_count,
                            count(*) FILTER (WHERE result = 'UPDATED') AS updated_count,
                            count(*) FILTER (WHERE result = 'UNCHANGED') AS unchanged_count,
                            count(*) FILTER (WHERE result = 'FAILED') AS failed_count,
                            count(*) FILTER (WHERE result = 'NOT_APPLIED') AS not_applied_count
                        FROM yumpoo.directory_sync_item
                        WHERE run_id = :runId
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new ItemCounts(
                        resultSet.getInt("created_count"),
                        resultSet.getInt("updated_count"),
                        resultSet.getInt("unchanged_count"),
                        resultSet.getInt("failed_count"),
                        resultSet.getInt("not_applied_count")
                ))
                .single();
    }

    private void publish(String eventType, DirectorySyncRunSnapshot run, EventActor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.runId());
        payload.put("triggerType", run.triggerType().name());
        payload.put("status", run.status().name());
        payload.put("pageCount", run.pageCount());
        payload.put("discoveredCount", run.counts().discovered());
        payload.put("stagedCount", run.counts().staged());
        payload.put("createdCount", run.counts().created());
        payload.put("updatedCount", run.counts().updated());
        payload.put("unchangedCount", run.counts().unchanged());
        payload.put("failedCount", run.counts().failed());
        payload.put("notAppliedCount", run.counts().notApplied());
        payload.put(
                "cursorTerminationMode",
                run.cursorTerminationMode() == null ? null : run.cursorTerminationMode().name()
        );
        payload.put("errorCode", run.errorCode());
        eventPort.append(new EventDraft(
                eventType,
                1,
                "DirectorySyncRun",
                run.runId(),
                run.rowVersion(),
                run.companyId(),
                actor,
                objectMapper.valueToTree(payload)
        ));
    }

    private static DirectorySyncRunSnapshot mapSnapshot(ResultSet resultSet, int rowNumber)
            throws SQLException {
        String termination = resultSet.getString("cursor_termination_mode");
        return new DirectorySyncRunSnapshot(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("company_id", UUID.class),
                DirectorySyncTriggerType.valueOf(resultSet.getString("trigger_type")),
                DirectorySyncRunPhase.valueOf(resultSet.getString("phase")),
                DirectorySyncRunStatus.valueOf(resultSet.getString("status")),
                termination == null
                        ? null
                        : DirectoryScanResult.CursorTerminationMode.valueOf(termination),
                resultSet.getInt("page_count"),
                resultSet.getBoolean("scan_complete"),
                new DirectorySyncCounts(
                        resultSet.getInt("discovered_count"),
                        resultSet.getInt("staged_count"),
                        resultSet.getInt("created_count"),
                        resultSet.getInt("updated_count"),
                        resultSet.getInt("unchanged_count"),
                        resultSet.getInt("left_count"),
                        resultSet.getInt("returned_count"),
                        resultSet.getInt("failed_count"),
                        resultSet.getInt("not_applied_count")
                ),
                resultSet.getString("error_code"),
                resultSet.getString("request_id"),
                resultSet.getLong("row_version"),
                resultSet.getTimestamp("started_at").toInstant(),
                resultSet.getTimestamp("finished_at") == null
                        ? null
                        : resultSet.getTimestamp("finished_at").toInstant()
        );
    }

    private static DirectoryOptionalField field(
            ResultSet resultSet,
            String stateColumn,
            String valueColumn
    ) throws SQLException {
        DirectoryOptionalField.State state = DirectoryOptionalField.State.valueOf(
                resultSet.getString(stateColumn)
        );
        return switch (state) {
            case PRESENT -> DirectoryOptionalField.present(resultSet.getString(valueColumn));
            case CLEAR -> DirectoryOptionalField.clear();
            case UNAVAILABLE -> DirectoryOptionalField.unavailable();
        };
    }

    private static String normalizeCursor(String cursor) {
        return cursor == null || cursor.isBlank() ? null : cursor;
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requireLease(int affectedRows) {
        if (affectedRows != 1) {
            throw new DirectorySyncLeaseLostException();
        }
    }

    private static void requireLeaseDuration(Duration duration) {
        Objects.requireNonNull(duration, "leaseDuration must not be null");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    private record ActiveRun(UUID runId, UUID leaseToken, Instant leaseUntil) {
    }

    private record ItemCounts(
            int created,
            int updated,
            int unchanged,
            int failed,
            int notApplied
    ) {
    }
}
