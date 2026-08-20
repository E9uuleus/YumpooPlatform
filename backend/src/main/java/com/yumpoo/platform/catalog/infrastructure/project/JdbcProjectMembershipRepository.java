package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.project.ProjectMembershipRepository;
import com.yumpoo.platform.catalog.domain.project.ProjectMembership;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class JdbcProjectMembershipRepository implements ProjectMembershipRepository {

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

    private static JdbcClient.StatementSpec nullable(
            JdbcClient.StatementSpec statement,
            String name,
            Object value,
            int sqlType
    ) {
        return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
    }
}
