package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.project.ProjectMembershipRepository;
import com.yumpoo.platform.catalog.application.project.ProjectMembershipModels.Access;
import com.yumpoo.platform.catalog.application.project.ProjectMembershipModels.ActorAccess;
import com.yumpoo.platform.catalog.application.project.ProjectMembershipModels.ListStatus;
import com.yumpoo.platform.catalog.domain.project.ProjectMembership;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Locale;

@Repository
public class JdbcProjectMembershipRepository implements ProjectMembershipRepository {

    private static final String COLUMNS = """
            id, company_id, project_id, user_id, status, joined_at, joined_by_user_id,
            removed_at, removed_by_user_id, remove_reason, row_version
            """;

    private static final String INSERT = """
            INSERT INTO yumpoo.project_membership (
                id, company_id, project_id, user_id, status, joined_at, joined_by_user_id,
                removed_at, removed_by_user_id, remove_reason, row_version
            ) VALUES (
                :id, :companyId, :projectId, :userId, :status, :joinedAt, :joinedByUserId,
                :removedAt, :removedByUserId, :removeReason, :rowVersion
            ) ON CONFLICT (project_id, user_id) DO NOTHING
            """;

    private final JdbcClient jdbcClient;

    public JdbcProjectMembershipRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean insert(ProjectMembership membership) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(INSERT)
                .param("id", membership.id())
                .param("companyId", membership.companyId())
                .param("projectId", membership.projectId())
                .param("userId", membership.userId())
                .param("status", membership.status().name())
                .param("joinedAt", OffsetDateTime.ofInstant(membership.joinedAt(), ZoneOffset.UTC))
                .param("joinedByUserId", membership.joinedByUserId())
                .param("rowVersion", membership.rowVersion());
        statement = nullable(statement, "removedAt", membership.removedAt() == null
                ? null : OffsetDateTime.ofInstant(membership.removedAt(), ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE);
        statement = nullable(statement, "removedByUserId", membership.removedByUserId(), Types.OTHER);
        statement = nullable(statement, "removeReason", membership.removeReason(), Types.VARCHAR);
        return statement.update() == 1;
    }

    @Override
    public Optional<ProjectMembership> find(UUID companyId, UUID projectId, UUID userId) {
        return find(companyId, projectId, userId, false);
    }

    @Override
    public Optional<ProjectMembership> lock(UUID companyId, UUID projectId, UUID userId) {
        return find(companyId, projectId, userId, true);
    }

    @Override
    public Optional<ProjectMembership> update(ProjectMembership membership, long expectedVersion) {
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                UPDATE yumpoo.project_membership SET status = :status,
                    joined_at = :joinedAt, joined_by_user_id = :joinedByUserId,
                    removed_at = :removedAt, removed_by_user_id = :removedByUserId,
                    remove_reason = :removeReason, row_version = row_version + 1
                WHERE company_id = :companyId AND project_id = :projectId
                  AND user_id = :userId AND row_version = :expectedVersion
                RETURNING %s
                """.formatted(COLUMNS)).param("status", membership.status().name())
                .param("joinedAt", utc(membership.joinedAt()))
                .param("joinedByUserId", membership.joinedByUserId());
        statement = nullable(statement, "removedAt",
                membership.removedAt() == null ? null : utc(membership.removedAt()),
                Types.TIMESTAMP_WITH_TIMEZONE);
        statement = nullable(statement, "removedByUserId", membership.removedByUserId(), Types.OTHER);
        statement = nullable(statement, "removeReason", membership.removeReason(), Types.VARCHAR);
        return statement.param("companyId", membership.companyId())
                .param("projectId", membership.projectId()).param("userId", membership.userId())
                .param("expectedVersion", expectedVersion).query(JdbcProjectMembershipRepository::map).optional();
    }

    @Override
    public List<ProjectMembership> findPage(UUID companyId, UUID projectId,
            ListStatus status, String query, OffsetPageRequest page) {
        String normalized = normalizeQuery(query);
        return jdbcClient.sql("SELECT m.id, m.company_id, m.project_id, m.user_id, m.status, "
                        + "m.joined_at, m.joined_by_user_id, m.removed_at, m.removed_by_user_id, "
                        + "m.remove_reason, m.row_version FROM yumpoo.project_membership m "
                        + "JOIN yumpoo.project p ON p.id=m.project_id AND p.company_id=m.company_id "
                        + "JOIN yumpoo.identity_user u ON u.id=m.user_id AND u.company_id=m.company_id "
                        + "WHERE m.company_id=:companyId AND m.project_id=:projectId "
                        + "AND (:allStatus OR m.status=:status) "
                        + "AND (:noQuery OR lower(u.display_name) LIKE :query ESCAPE '\\') "
                        + "ORDER BY CASE WHEN m.user_id=p.owner_user_id THEN 0 WHEN m.status='ACTIVE' THEN 1 ELSE 2 END, "
                        + "CASE WHEN m.status='ACTIVE' THEN m.joined_at END, m.removed_at, m.id "
                        + "LIMIT :limit OFFSET :offset")
                .param("companyId", companyId).param("projectId", projectId)
                .param("allStatus", status == ListStatus.ALL)
                .param("status", status == ListStatus.ALL ? "ACTIVE" : status.name())
                .param("noQuery", normalized == null)
                .param("query", normalized == null ? "" : "%" + escapeLike(normalized) + "%")
                .param("limit", page.size()).param("offset", (long) page.page() * page.size())
                .query(JdbcProjectMembershipRepository::map).list();
    }

    @Override
    public long count(UUID companyId, UUID projectId, ListStatus status, String query) {
        String normalized = normalizeQuery(query);
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.project_membership m "
                        + "JOIN yumpoo.identity_user u ON u.id=m.user_id AND u.company_id=m.company_id "
                        + "WHERE m.company_id=:companyId AND m.project_id=:projectId "
                        + "AND (:allStatus OR m.status=:status) "
                        + "AND (:noQuery OR lower(u.display_name) LIKE :query ESCAPE '\\')")
                .param("companyId", companyId).param("projectId", projectId)
                .param("allStatus", status == ListStatus.ALL)
                .param("status", status == ListStatus.ALL ? "ACTIVE" : status.name())
                .param("noQuery", normalized == null)
                .param("query", normalized == null ? "" : "%" + escapeLike(normalized) + "%")
                .query(Long.class).single();
    }

    @Override
    public Map<UUID, ProjectMembership> findByUsers(UUID companyId, UUID projectId,
                                                    Collection<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM yumpoo.project_membership "
                        + "WHERE company_id=:companyId AND project_id=:projectId AND user_id IN (:userIds)")
                .param("companyId", companyId).param("projectId", projectId).param("userIds", userIds)
                .query(JdbcProjectMembershipRepository::map).list().stream()
                .collect(Collectors.toUnmodifiableMap(ProjectMembership::userId, Function.identity()));
    }

    @Override
    public boolean existsActive(UUID companyId, UUID projectId, UUID userId) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM yumpoo.project_membership "
                        + "WHERE company_id=:companyId AND project_id=:projectId "
                        + "AND user_id=:userId AND status='ACTIVE')")
                .param("companyId", companyId).param("projectId", projectId)
                .param("userId", userId).query(Boolean.class).single();
    }

    @Override
    public Optional<Access> findVisible(CurrentActor actor, UUID projectId) {
        boolean admin = actor.hasRole(PlatformRoleCode.COMPANY_ADMIN);
        return jdbcClient.sql("""
                SELECT p.id, p.company_id, p.lifecycle, p.template_key, p.template_version,
                       p.row_version AS project_version,
                       m.row_version AS membership_version,
                       CASE WHEN p.owner_user_id=:actorUserId THEN 'OWNER'
                            WHEN m.id IS NOT NULL THEN 'MEMBER'
                            ELSE 'COMPANY_ADMIN_READ_ONLY' END AS actor_access
                FROM yumpoo.project p
                LEFT JOIN yumpoo.project_membership m
                  ON m.project_id=p.id AND m.user_id=:actorUserId AND m.status='ACTIVE'
                WHERE p.id=:projectId AND p.company_id=:companyId AND (:admin OR m.id IS NOT NULL)
                """).param("actorUserId", actor.userId()).param("projectId", projectId)
                .param("companyId", actor.companyId()).param("admin", admin)
                .query(JdbcProjectMembershipRepository::mapAccess)
                .optional();
    }

    @Override
    public Map<UUID, Access> findVisible(CurrentActor actor, Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) return Map.of();
        boolean admin = actor.hasRole(PlatformRoleCode.COMPANY_ADMIN);
        return jdbcClient.sql("""
                SELECT p.id, p.company_id, p.lifecycle, p.template_key, p.template_version,
                       p.row_version AS project_version,
                       m.row_version AS membership_version,
                       CASE WHEN p.owner_user_id=:actorUserId THEN 'OWNER'
                            WHEN m.id IS NOT NULL THEN 'MEMBER'
                            ELSE 'COMPANY_ADMIN_READ_ONLY' END AS actor_access
                FROM yumpoo.project p
                LEFT JOIN yumpoo.project_membership m
                  ON m.project_id=p.id AND m.user_id=:actorUserId AND m.status='ACTIVE'
                WHERE p.id IN (:projectIds) AND p.company_id=:companyId
                  AND (:admin OR m.id IS NOT NULL)
                """).param("actorUserId", actor.userId()).param("projectIds", projectIds)
                .param("companyId", actor.companyId()).param("admin", admin)
                .query(JdbcProjectMembershipRepository::mapAccess).list().stream()
                .collect(Collectors.toUnmodifiableMap(Access::projectId, Function.identity()));
    }

    private Optional<ProjectMembership> find(UUID companyId, UUID projectId, UUID userId, boolean lock) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM yumpoo.project_membership "
                        + "WHERE company_id=:companyId AND project_id=:projectId AND user_id=:userId"
                        + (lock ? " FOR UPDATE" : ""))
                .param("companyId", companyId).param("projectId", projectId).param("userId", userId)
                .query(JdbcProjectMembershipRepository::map).optional();
    }

    private static ProjectMembership map(ResultSet rs, int row) throws SQLException {
        return new ProjectMembership(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                com.yumpoo.platform.catalog.domain.project.ProjectMembershipStatus.valueOf(rs.getString("status")),
                instant(rs, "joined_at"), rs.getObject("joined_by_user_id", UUID.class),
                nullableInstant(rs, "removed_at"), rs.getObject("removed_by_user_id", UUID.class),
                rs.getString("remove_reason"), rs.getLong("row_version"));
    }

    private static Access mapAccess(ResultSet rs, int row) throws SQLException {
        return new Access(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getString("lifecycle"),
                ActorAccess.valueOf(rs.getString("actor_access")), rs.getString("template_key"),
                rs.getInt("template_version"), rs.getLong("project_version"),
                rs.getObject("membership_version") == null ? OptionalLong.empty()
                        : OptionalLong.of(rs.getLong("membership_version")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static String normalizeQuery(String query) {
        return query == null || query.isBlank() ? null : query.strip().toLowerCase(Locale.ROOT);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static JdbcClient.StatementSpec nullable(
            JdbcClient.StatementSpec statement,
            String name,
            Object value,
            int sqlType
    ) {
        return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
    }
}
