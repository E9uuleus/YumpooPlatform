package com.yumpoo.platform.identityaccess.infrastructure.authorization;

import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentPage;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentQuery;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentQueryRepository;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentStatus;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcRoleAssignmentQueryRepository implements RoleAssignmentQueryRepository {

    private final JdbcClient jdbcClient;

    public JdbcRoleAssignmentQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public RoleAssignmentPage find(RoleAssignmentQuery query) {
        List<Object> parameters = new ArrayList<>();
        String predicate = predicate(query, parameters);
        long total = count(query.companyId(), predicate, parameters);

        String sql = """
                SELECT id, company_id, user_id, role_code, scope_type, scope_id,
                       status, granted_at, revoked_at, row_version
                FROM yumpoo.platform_role_assignment
                WHERE company_id = ?
                """ + predicate + " ORDER BY granted_at DESC, id ASC LIMIT ? OFFSET ?";
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql).param(query.companyId());
        for (Object parameter : parameters) {
            statement = statement.param(parameter);
        }
        statement = statement.param(query.pageSize()).param((long) query.page() * query.pageSize());
        List<RoleAssignmentView> items = statement
                .query(JdbcRoleAssignmentQueryRepository::mapView)
                .list();
        return new RoleAssignmentPage(items, query.page(), query.pageSize(), total);
    }

    private long count(UUID companyId, String predicate, List<Object> parameters) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(
                "SELECT count(*) FROM yumpoo.platform_role_assignment WHERE company_id = ?" + predicate)
                .param(companyId);
        for (Object parameter : parameters) {
            statement = statement.param(parameter);
        }
        return statement.query(Long.class).single();
    }

    private String predicate(RoleAssignmentQuery query, List<Object> parameters) {
        StringBuilder sql = new StringBuilder();
        if (query.userId() != null) {
            sql.append(" AND user_id = ?");
            parameters.add(query.userId());
        }
        if (query.role() != null) {
            sql.append(" AND role_code = ?");
            parameters.add(query.role().name());
        }
        if (query.status() != null) {
            sql.append(" AND status = ?");
            parameters.add(query.status().name());
        }
        return sql.toString();
    }

    private static RoleAssignmentView mapView(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new RoleAssignmentView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("company_id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                ManagedPlatformRole.valueOf(resultSet.getString("role_code")),
                resultSet.getString("scope_type"),
                resultSet.getObject("scope_id", UUID.class),
                RoleAssignmentStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("granted_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("revoked_at", OffsetDateTime.class) == null
                        ? null : resultSet.getObject("revoked_at", OffsetDateTime.class).toInstant(),
                resultSet.getLong("row_version")
        );
    }
}
