package com.yumpoo.platform.catalog.infrastructure.workspace;

import com.yumpoo.platform.catalog.application.workspace.WorkspaceListStatus;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceRepository;
import com.yumpoo.platform.catalog.domain.workspace.Workspace;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkspaceRepository implements WorkspaceRepository {

    private static final String COLUMNS = """
            id,
            company_id,
            code,
            name,
            description,
            sort_order,
            status,
            row_version,
            created_at,
            created_by_user_id,
            updated_at,
            updated_by_user_id
            """;

    private static final String FIND_ALL = """
            SELECT
            """ + COLUMNS + """
            FROM yumpoo.workspace
            WHERE company_id = :companyId
              AND ((:includeActive AND status = 'ACTIVE')
                   OR (:includeArchived AND status = 'ARCHIVED'))
            ORDER BY sort_order, name, id
            """;

    private static final String FIND_BY_ID = """
            SELECT
            """ + COLUMNS + """
            FROM yumpoo.workspace
            WHERE company_id = :companyId
              AND id = :workspaceId
            """;

    private static final String FIND_ACTIVE_BY_ID = FIND_BY_ID + " AND status = 'ACTIVE'";

    private static final String FIND_MAIN_FOR_SHARE = """
            SELECT
            """ + COLUMNS + """
            FROM yumpoo.workspace
            WHERE company_id = :companyId
              AND code = 'MAIN'
              AND status = 'ACTIVE'
            FOR SHARE
            """;

    private static final String UPDATE_DETAILS = """
            UPDATE yumpoo.workspace
            SET name = :name,
                description = :description,
                row_version = row_version + 1,
                updated_at = :updatedAt,
                updated_by_user_id = :updatedByUserId
            WHERE company_id = :companyId
              AND id = :id
              AND row_version = :expectedRowVersion
            RETURNING
            """ + COLUMNS;

    private final JdbcClient jdbcClient;

    public JdbcWorkspaceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<Workspace> findAll(UUID companyId, WorkspaceListStatus status) {
        return jdbcClient.sql(FIND_ALL)
                .param("companyId", companyId)
                .param("includeActive", status.includeActive())
                .param("includeArchived", status.includeArchived())
                .query(JdbcWorkspaceRepository::map)
                .list();
    }

    @Override
    public Optional<Workspace> findById(UUID companyId, UUID workspaceId) {
        return find(FIND_BY_ID, companyId, workspaceId);
    }

    @Override
    public Optional<Workspace> findActiveById(UUID companyId, UUID workspaceId) {
        return find(FIND_ACTIVE_BY_ID, companyId, workspaceId);
    }

    @Override
    public Optional<Workspace> findMainForShare(UUID companyId) {
        return jdbcClient.sql(FIND_MAIN_FOR_SHARE)
                .param("companyId", companyId)
                .query(JdbcWorkspaceRepository::map)
                .optional();
    }

    @Override
    public Optional<Workspace> updateDetails(Workspace workspace, long expectedRowVersion) {
        return jdbcClient.sql(UPDATE_DETAILS)
                .param("name", workspace.name())
                .param("description", workspace.description())
                .param("updatedAt", utc(workspace.updatedAt()))
                .param("updatedByUserId", workspace.updatedByUserId())
                .param("companyId", workspace.companyId())
                .param("id", workspace.id())
                .param("expectedRowVersion", expectedRowVersion)
                .query(JdbcWorkspaceRepository::map)
                .optional();
    }

    private Optional<Workspace> find(String sql, UUID companyId, UUID workspaceId) {
        return jdbcClient.sql(sql)
                .param("companyId", companyId)
                .param("workspaceId", workspaceId)
                .query(JdbcWorkspaceRepository::map)
                .optional();
    }

    private static Workspace map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Workspace(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("company_id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getInt("sort_order"),
                WorkspaceStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("row_version"),
                instant(resultSet, "created_at"),
                resultSet.getObject("created_by_user_id", UUID.class),
                instant(resultSet, "updated_at"),
                resultSet.getObject("updated_by_user_id", UUID.class)
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException(column + " must not be null");
        }
        return value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
