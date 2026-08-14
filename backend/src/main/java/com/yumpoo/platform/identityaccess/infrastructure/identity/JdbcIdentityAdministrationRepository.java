package com.yumpoo.platform.identityaccess.infrastructure.identity;

import com.yumpoo.platform.identityaccess.application.administration.DirectoryRunQuery;
import com.yumpoo.platform.identityaccess.application.administration.DirectoryRuntimeSnapshot;
import com.yumpoo.platform.identityaccess.application.administration.DirectorySyncFailurePage;
import com.yumpoo.platform.identityaccess.application.administration.DirectorySyncFailureView;
import com.yumpoo.platform.identityaccess.application.administration.DirectorySyncRunPage;
import com.yumpoo.platform.identityaccess.application.administration.DirectorySyncRunView;
import com.yumpoo.platform.identityaccess.application.administration.IdentityAdministrationRepository;
import com.yumpoo.platform.identityaccess.application.administration.IdentityMemberPage;
import com.yumpoo.platform.identityaccess.application.administration.IdentityMemberQuery;
import com.yumpoo.platform.identityaccess.application.administration.IdentityMemberView;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncCounts;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcIdentityAdministrationRepository implements IdentityAdministrationRepository {

    private static final String MEMBER_COLUMNS = """
            SELECT
                u.id, u.display_name, e.external_user_id, u.email, u.mobile,
                u.department_summary, u.employment_status, u.account_status,
                u.directory_synced_at, u.left_at, u.account_disabled_at,
                u.account_disabled_by_user_id, u.authorization_version, u.row_version,
                COALESCE((
                    SELECT string_agg(a.role_code, ',' ORDER BY a.role_code)
                    FROM yumpoo.platform_role_assignment a
                    WHERE a.company_id = u.company_id
                      AND a.user_id = u.id
                      AND a.status = 'ACTIVE'
                ), '') AS platform_roles
            FROM yumpoo.identity_user u
            JOIN yumpoo.external_identity e
              ON e.company_id = u.company_id
             AND e.user_id = u.id
             AND e.provider = 'WECOM'
            """;

    private static final String MEMBER_FILTER = """
            WHERE u.company_id = :companyId
              AND (:name IS NULL OR position(lower(CAST(:name AS varchar)) in lower(u.display_name)) > 0)
              AND (:externalUserId IS NULL OR e.external_user_id = CAST(:externalUserId AS varchar))
              AND (:employmentStatus IS NULL OR u.employment_status = CAST(:employmentStatus AS varchar))
              AND (:accountStatus IS NULL OR u.account_status = CAST(:accountStatus AS varchar))
            """;

    private static final String RUN_COLUMNS = """
            SELECT
                r.id, r.trigger_type, r.triggered_by_user_id,
                actor.display_name AS triggered_by_display_name,
                r.phase, r.status, r.cursor_termination_mode, r.page_count,
                r.scan_complete, r.discovered_count, r.staged_count,
                r.created_count, r.updated_count, r.unchanged_count,
                r.left_count, r.returned_count, r.failed_count,
                r.not_applied_count, r.error_code, r.error_summary,
                r.request_id, r.row_version, r.started_at, r.finished_at
            FROM yumpoo.directory_sync_run r
            LEFT JOIN yumpoo.identity_user actor
              ON actor.company_id = r.company_id
             AND actor.id = r.triggered_by_user_id
            """;

    private static final String RUN_FILTER = """
            WHERE r.company_id = :companyId
              AND (:status IS NULL OR r.status = CAST(:status AS varchar))
              AND (:triggerType IS NULL OR r.trigger_type = CAST(:triggerType AS varchar))
            """;

    private final JdbcClient jdbcClient;

    public JdbcIdentityAdministrationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public IdentityMemberPage findMembers(UUID companyId, IdentityMemberQuery query) {
        OffsetPageRequest page = query.pageRequest();
        List<IdentityMemberView> items = bindMemberFilters(
                jdbcClient.sql(MEMBER_COLUMNS + MEMBER_FILTER + """
                        ORDER BY lower(u.display_name), u.id
                        LIMIT :limit OFFSET :offset
                        """),
                companyId,
                query
        )
                .param("limit", page.size())
                .param("offset", Math.multiplyExact(page.page(), page.size()))
                .query(JdbcIdentityAdministrationRepository::mapMember)
                .list();
        long total = bindMemberFilters(
                jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.identity_user u
                        JOIN yumpoo.external_identity e
                          ON e.company_id = u.company_id
                         AND e.user_id = u.id
                         AND e.provider = 'WECOM'
                        """ + MEMBER_FILTER),
                companyId,
                query
        ).query(Long.class).single();
        return new IdentityMemberPage(items, total);
    }

    @Override
    public Optional<IdentityMemberView> findMember(UUID companyId, UUID userId) {
        return jdbcClient.sql(MEMBER_COLUMNS + """
                        WHERE u.company_id = :companyId AND u.id = :userId
                        """)
                .param("companyId", companyId)
                .param("userId", userId)
                .query(JdbcIdentityAdministrationRepository::mapMember)
                .optional();
    }

    @Override
    public DirectorySyncRunPage findRuns(UUID companyId, DirectoryRunQuery query) {
        OffsetPageRequest page = query.pageRequest();
        List<DirectorySyncRunView> items = bindRunFilters(
                jdbcClient.sql(RUN_COLUMNS + RUN_FILTER + """
                        ORDER BY r.started_at DESC, r.id DESC
                        LIMIT :limit OFFSET :offset
                        """),
                companyId,
                query
        )
                .param("limit", page.size())
                .param("offset", Math.multiplyExact(page.page(), page.size()))
                .query(JdbcIdentityAdministrationRepository::mapRun)
                .list();
        long total = bindRunFilters(
                jdbcClient.sql("SELECT count(*) FROM yumpoo.directory_sync_run r " + RUN_FILTER),
                companyId,
                query
        ).query(Long.class).single();
        return new DirectorySyncRunPage(items, total);
    }

    @Override
    public Optional<DirectorySyncRunView> findRun(UUID companyId, UUID runId) {
        return jdbcClient.sql(RUN_COLUMNS + """
                        WHERE r.company_id = :companyId AND r.id = :runId
                        """)
                .param("companyId", companyId)
                .param("runId", runId)
                .query(JdbcIdentityAdministrationRepository::mapRun)
                .optional();
    }

    @Override
    public DirectorySyncFailurePage findFailures(
            UUID companyId,
            UUID runId,
            OffsetPageRequest pageRequest
    ) {
        List<DirectorySyncFailureView> items = jdbcClient.sql("""
                        SELECT
                            CASE
                                WHEN char_length(i.external_user_id) <= 4
                                    THEN left(i.external_user_id, 1) || '***'
                                ELSE left(i.external_user_id, 2) || '***'
                                    || right(i.external_user_id, 2)
                            END AS masked_member_reference,
                            i.action, i.result, i.error_code
                        FROM yumpoo.directory_sync_item i
                        JOIN yumpoo.directory_sync_run r ON r.id = i.run_id
                        WHERE r.company_id = :companyId
                          AND i.run_id = :runId
                          AND i.result IN ('FAILED', 'NOT_APPLIED')
                        ORDER BY i.external_user_id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("companyId", companyId)
                .param("runId", runId)
                .param("limit", pageRequest.size())
                .param("offset", Math.multiplyExact(pageRequest.page(), pageRequest.size()))
                .query((rs, row) -> new DirectorySyncFailureView(
                        rs.getString("masked_member_reference"),
                        rs.getString("action"),
                        rs.getString("result"),
                        rs.getString("error_code")
                ))
                .list();
        long total = jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.directory_sync_item i
                        JOIN yumpoo.directory_sync_run r ON r.id = i.run_id
                        WHERE r.company_id = :companyId
                          AND i.run_id = :runId
                          AND i.result IN ('FAILED', 'NOT_APPLIED')
                        """)
                .param("companyId", companyId)
                .param("runId", runId)
                .query(Long.class)
                .single();
        return new DirectorySyncFailurePage(items, total);
    }

    @Override
    public DirectoryRuntimeSnapshot runtimeStatus(UUID companyId) {
        return jdbcClient.sql("""
                        SELECT
                            (SELECT id FROM yumpoo.directory_sync_run
                             WHERE company_id = :companyId AND status = 'RUNNING'
                             ORDER BY started_at DESC LIMIT 1) AS active_run_id,
                            (SELECT finished_at FROM yumpoo.directory_sync_run
                             WHERE company_id = :companyId AND status = 'SUCCEEDED'
                             ORDER BY finished_at DESC, id DESC LIMIT 1) AS last_successful_run_at,
                            (SELECT COALESCE(finished_at, started_at)
                             FROM yumpoo.directory_sync_run
                             WHERE company_id = :companyId
                               AND status IN ('PARTIALLY_SUCCEEDED', 'FAILED')
                             ORDER BY started_at DESC, id DESC LIMIT 1) AS last_problem_at,
                            (SELECT error_code FROM yumpoo.directory_sync_run
                             WHERE company_id = :companyId
                               AND status IN ('PARTIALLY_SUCCEEDED', 'FAILED')
                             ORDER BY started_at DESC, id DESC LIMIT 1) AS last_problem_code
                        """)
                .param("companyId", companyId)
                .query((rs, row) -> new DirectoryRuntimeSnapshot(
                        rs.getObject("active_run_id", UUID.class),
                        instant(rs, "last_successful_run_at"),
                        instant(rs, "last_problem_at"),
                        rs.getString("last_problem_code")
                ))
                .single();
    }

    private static JdbcClient.StatementSpec bindMemberFilters(
            JdbcClient.StatementSpec statement,
            UUID companyId,
            IdentityMemberQuery query
    ) {
        return statement
                .param("companyId", companyId)
                .param("name", query.name())
                .param("externalUserId", query.externalUserId())
                .param("employmentStatus", query.employmentStatus())
                .param("accountStatus", query.accountStatus());
    }

    private static JdbcClient.StatementSpec bindRunFilters(
            JdbcClient.StatementSpec statement,
            UUID companyId,
            DirectoryRunQuery query
    ) {
        return statement
                .param("companyId", companyId)
                .param("status", query.status())
                .param("triggerType", query.triggerType());
    }

    private static IdentityMemberView mapMember(ResultSet rs, int row) throws SQLException {
        long rowVersion = rs.getLong("row_version");
        return new IdentityMemberView(
                rs.getObject("id", UUID.class),
                rs.getString("display_name"),
                rs.getString("external_user_id"),
                rs.getString("email"),
                rs.getString("mobile"),
                rs.getString("department_summary"),
                rs.getString("employment_status"),
                rs.getString("account_status"),
                instant(rs, "directory_synced_at"),
                instant(rs, "left_at"),
                instant(rs, "account_disabled_at"),
                rs.getObject("account_disabled_by_user_id", UUID.class),
                roles(rs.getString("platform_roles")),
                rs.getLong("authorization_version"),
                rowVersion,
                "\"" + rowVersion + "\""
        );
    }

    private static DirectorySyncRunView mapRun(ResultSet rs, int row) throws SQLException {
        return new DirectorySyncRunView(
                rs.getObject("id", UUID.class),
                rs.getString("trigger_type"),
                rs.getObject("triggered_by_user_id", UUID.class),
                rs.getString("triggered_by_display_name"),
                rs.getString("phase"),
                rs.getString("status"),
                rs.getString("cursor_termination_mode"),
                rs.getInt("page_count"),
                rs.getBoolean("scan_complete"),
                new DirectorySyncCounts(
                        rs.getInt("discovered_count"),
                        rs.getInt("staged_count"),
                        rs.getInt("created_count"),
                        rs.getInt("updated_count"),
                        rs.getInt("unchanged_count"),
                        rs.getInt("left_count"),
                        rs.getInt("returned_count"),
                        rs.getInt("failed_count"),
                        rs.getInt("not_applied_count")
                ),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                rs.getString("request_id"),
                rs.getLong("row_version"),
                instant(rs, "started_at"),
                instant(rs, "finished_at")
        );
    }

    private static Set<ManagedPlatformRole> roles(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<ManagedPlatformRole> roles = new LinkedHashSet<>();
        Arrays.stream(value.split(","))
                .map(ManagedPlatformRole::valueOf)
                .forEach(roles::add);
        return Set.copyOf(roles);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
