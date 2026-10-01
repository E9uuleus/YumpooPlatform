package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.ConnectColumnRepository;
import com.yumpoo.platform.workitem.domain.ConnectColumn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcConnectColumnRepository implements ConnectColumnRepository {
    private static final String SELECT = """
            SELECT c.*, ARRAY(SELECT t.target_project_id FROM yumpoo.work_item_connect_column_target t
                WHERE t.company_id=c.company_id AND t.column_id=c.id ORDER BY t.target_project_id) AS targets
            FROM yumpoo.work_item_connect_column c
            """;
    private final JdbcClient jdbc;

    public JdbcConnectColumnRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public long lockCatalog(UUID companyId, UUID projectId, Instant now) {
        jdbc.sql("""
                INSERT INTO yumpoo.work_item_connect_column_catalog (company_id, project_id, updated_at)
                VALUES (:companyId, :projectId, :now) ON CONFLICT (project_id) DO NOTHING
                """).param("companyId", companyId).param("projectId", projectId).param("now", timestamp(now)).update();
        return jdbc.sql("""
                SELECT row_version FROM yumpoo.work_item_connect_column_catalog
                WHERE company_id=:companyId AND project_id=:projectId FOR UPDATE
                """).param("companyId", companyId).param("projectId", projectId).query(Long.class).single();
    }

    @Override
    public void bumpCatalog(UUID companyId, UUID projectId, Instant now) {
        if (jdbc.sql("""
                UPDATE yumpoo.work_item_connect_column_catalog SET row_version=row_version+1, updated_at=:now
                WHERE company_id=:companyId AND project_id=:projectId
                """).param("companyId", companyId).param("projectId", projectId)
                .param("now", timestamp(now)).update() != 1) throw new IllegalStateException("column catalog missing");
    }

    @Override
    public List<ConnectColumn> findActive(UUID companyId, UUID projectId) {
        return jdbc.sql(SELECT + " WHERE c.company_id=:companyId AND c.project_id=:projectId"
                        + " AND c.deleted_at IS NULL ORDER BY c.created_at, c.id")
                .param("companyId", companyId).param("projectId", projectId).query(JdbcConnectColumnRepository::map).list();
    }

    @Override
    public List<IncomingColumn> findIncoming(UUID companyId, UUID projectId) {
        return jdbc.sql("""
                SELECT c.id, c.name, c.project_id FROM yumpoo.work_item_connect_column c
                JOIN yumpoo.work_item_connect_column_target t ON t.column_id=c.id AND t.company_id=c.company_id
                WHERE c.company_id=:companyId AND t.target_project_id=:projectId AND c.deleted_at IS NULL
                ORDER BY c.created_at, c.id
                """).param("companyId", companyId).param("projectId", projectId)
                .query((row, n) -> new IncomingColumn(row.getObject("id", UUID.class), row.getString("name"),
                        row.getObject("project_id", UUID.class))).list();
    }

    @Override
    public Optional<ConnectColumn> find(UUID companyId, UUID projectId, UUID columnId) {
        return select(companyId, projectId, columnId, "");
    }

    @Override
    public Optional<ConnectColumn> lock(UUID companyId, UUID projectId, UUID columnId, boolean forShare) {
        var locked = jdbc.sql("SELECT id FROM yumpoo.work_item_connect_column c"
                        + " WHERE company_id=:companyId AND project_id=:projectId AND id=:id"
                        + (forShare ? " FOR SHARE OF c" : " FOR UPDATE OF c"))
                .param("companyId", companyId).param("projectId", projectId).param("id", columnId).query(UUID.class).optional();
        // Target rows may have changed while the row lock waited; use a fresh statement snapshot.
        return locked.isEmpty() ? Optional.empty() : find(companyId, projectId, columnId);
    }

    private Optional<ConnectColumn> select(UUID companyId, UUID projectId, UUID columnId, String lock) {
        return jdbc.sql(SELECT + " WHERE c.company_id=:companyId AND c.project_id=:projectId AND c.id=:id" + lock)
                .param("companyId", companyId).param("projectId", projectId).param("id", columnId)
                .query(JdbcConnectColumnRepository::map).optional();
    }

    @Override
    public long countActive(UUID companyId, UUID projectId) {
        return jdbc.sql("""
                SELECT count(*) FROM yumpoo.work_item_connect_column
                WHERE company_id=:companyId AND project_id=:projectId AND deleted_at IS NULL
                """).param("companyId", companyId).param("projectId", projectId).query(Long.class).single();
    }

    @Override
    public boolean nameExists(UUID companyId, UUID projectId, String name, UUID excludingId) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM yumpoo.work_item_connect_column
                WHERE company_id=:companyId AND project_id=:projectId AND normalized_name=lower(:name)
                  AND (:id::uuid IS NULL OR id<>:id) AND deleted_at IS NULL)
                """).param("companyId", companyId).param("projectId", projectId).param("name", name)
                .param("id", excludingId, Types.OTHER).query(Boolean.class).single();
    }

    @Override
    public boolean insert(ConnectColumn column) {
        return parameters(jdbc.sql("""
                INSERT INTO yumpoo.work_item_connect_column (id, company_id, project_id, name, normalized_name,
                    row_version, created_by_user_id, created_at, updated_by_user_id, updated_at)
                VALUES (:id, :companyId, :projectId, :name, lower(:name), :version,
                    :createdBy, :createdAt, :updatedBy, :updatedAt) ON CONFLICT DO NOTHING
                """), column).update() == 1;
    }

    @Override
    public boolean update(ConnectColumn column, long expectedVersion) {
        return parameters(jdbc.sql("""
                UPDATE yumpoo.work_item_connect_column SET name=:name, normalized_name=lower(:name),
                    row_version=:version, updated_by_user_id=:updatedBy, updated_at=:updatedAt,
                    deleted_by_user_id=:deletedBy, deleted_at=:deletedAt
                WHERE company_id=:companyId AND project_id=:projectId AND id=:id AND row_version=:expected
                """), column).param("expected", expectedVersion)
                .param("deletedBy", column.deletedByUserId(), Types.OTHER)
                .param("deletedAt", timestamp(column.deletedAt()), Types.TIMESTAMP_WITH_TIMEZONE).update() == 1;
    }

    @Override
    public void replaceTargets(ConnectColumn column) {
        jdbc.sql("DELETE FROM yumpoo.work_item_connect_column_target WHERE company_id=:companyId AND column_id=:id")
                .param("companyId", column.companyId()).param("id", column.id()).update();
        for (UUID target : column.targetProjectIds())
            jdbc.sql("""
                    INSERT INTO yumpoo.work_item_connect_column_target (column_id, company_id, target_project_id)
                    VALUES (:id, :companyId, :target)
                    """).param("id", column.id()).param("companyId", column.companyId()).param("target", target).update();
    }

    private static JdbcClient.StatementSpec parameters(JdbcClient.StatementSpec statement, ConnectColumn column) {
        return statement.param("id", column.id()).param("companyId", column.companyId())
                .param("projectId", column.projectId()).param("name", column.name())
                .param("version", column.rowVersion())
                .param("createdBy", column.createdByUserId()).param("createdAt", timestamp(column.createdAt()))
                .param("updatedBy", column.updatedByUserId()).param("updatedAt", timestamp(column.updatedAt()));
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static ConnectColumn map(ResultSet row, int n) throws SQLException {
        return new ConnectColumn(row.getObject("id", UUID.class), row.getObject("company_id", UUID.class),
                row.getObject("project_id", UUID.class), row.getString("name"),
                Arrays.asList((UUID[]) row.getArray("targets").getArray()), row.getLong("row_version"),
                row.getObject("created_by_user_id", UUID.class), row.getTimestamp("created_at").toInstant(),
                row.getObject("updated_by_user_id", UUID.class), row.getTimestamp("updated_at").toInstant(),
                row.getObject("deleted_by_user_id", UUID.class),
                row.getTimestamp("deleted_at") == null ? null : row.getTimestamp("deleted_at").toInstant());
    }
}
