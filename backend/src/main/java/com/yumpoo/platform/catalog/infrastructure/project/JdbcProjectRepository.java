package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.project.ProjectRepository;
import com.yumpoo.platform.catalog.domain.project.Project;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcProjectRepository implements ProjectRepository {

    private static final String COLUMNS = """
            id, company_id, workspace_id, project_code, name, description, project_type,
            lifecycle, owner_user_id, template_key, template_version, customer_name,
            customer_reference, delivery_site, contact_note, row_version, created_at,
            created_by_user_id, updated_at, updated_by_user_id, activated_at, archived_at
            """;

    private static final String INSERT = """
            INSERT INTO yumpoo.project (
                id, company_id, workspace_id, project_code, name, description,
                project_type, lifecycle, owner_user_id, template_key, template_version,
                customer_name, customer_reference, delivery_site, contact_note,
                row_version, created_at, created_by_user_id, updated_at, updated_by_user_id,
                activated_at, archived_at
            ) VALUES (
                :id, :companyId, :workspaceId, :code, :name, :description,
                :projectType, :lifecycle, :ownerUserId, :templateKey, :templateVersion,
                :customerName, :customerReference, :deliverySite, :contactNote,
                :rowVersion, :createdAt, :createdByUserId, :updatedAt, :updatedByUserId,
                :activatedAt, :archivedAt
            ) ON CONFLICT (company_id, project_code) DO NOTHING
            """;

    private final JdbcClient jdbcClient;

    public JdbcProjectRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean insert(Project project) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(INSERT)
                .param("id", project.id())
                .param("companyId", project.companyId())
                .param("workspaceId", project.workspaceId())
                .param("code", project.code())
                .param("name", project.name())
                .param("projectType", project.projectType().name())
                .param("lifecycle", project.lifecycle().name())
                .param("ownerUserId", project.ownerUserId())
                .param("templateKey", project.templateKey())
                .param("templateVersion", project.templateVersion())
                .param("rowVersion", project.rowVersion())
                .param("createdAt", OffsetDateTime.ofInstant(project.createdAt(), ZoneOffset.UTC))
                .param("createdByUserId", project.createdByUserId())
                .param("updatedAt", OffsetDateTime.ofInstant(project.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", project.updatedByUserId());
        statement = nullable(statement, "description", project.description(), Types.VARCHAR);
        statement = nullable(statement, "customerName", project.customerName(), Types.VARCHAR);
        statement = nullable(statement, "customerReference", project.customerReference(), Types.VARCHAR);
        statement = nullable(statement, "deliverySite", project.deliverySite(), Types.VARCHAR);
        statement = nullable(statement, "contactNote", project.contactNote(), Types.VARCHAR);
        statement = nullable(statement, "activatedAt", project.activatedAt() == null
                ? null : OffsetDateTime.ofInstant(project.activatedAt(), ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE);
        statement = nullable(statement, "archivedAt", project.archivedAt() == null
                ? null : OffsetDateTime.ofInstant(project.archivedAt(), ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE);
        return statement.update() == 1;
    }

    @Override
    public Optional<Project> findById(UUID companyId, UUID projectId) {
        return find(companyId, projectId, false);
    }

    @Override
    public Optional<Project> lockById(UUID companyId, UUID projectId) {
        return find(companyId, projectId, true);
    }

    @Override
    public Optional<Project> reassignOwner(Project project, long expectedVersion) {
        return jdbcClient.sql("""
                UPDATE yumpoo.project SET owner_user_id = :ownerUserId,
                    row_version = row_version + 1, updated_at = :updatedAt,
                    updated_by_user_id = :updatedByUserId
                WHERE company_id = :companyId AND id = :id
                  AND lifecycle <> 'ARCHIVED' AND row_version = :expectedVersion
                RETURNING %s
                """.formatted(COLUMNS))
                .param("ownerUserId", project.ownerUserId())
                .param("updatedAt", OffsetDateTime.ofInstant(project.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", project.updatedByUserId())
                .param("companyId", project.companyId()).param("id", project.id())
                .param("expectedVersion", expectedVersion).query(JdbcProjectRepository::map).optional();
    }

    private Optional<Project> find(UUID companyId, UUID projectId, boolean lock) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM yumpoo.project "
                        + "WHERE company_id = :companyId AND id = :projectId"
                        + (lock ? " FOR UPDATE" : ""))
                .param("companyId", companyId).param("projectId", projectId)
                .query(JdbcProjectRepository::map).optional();
    }

    @Override
    public List<Project> findGovernedByOwner(UUID companyId, UUID ownerUserId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM yumpoo.project "
                        + "WHERE company_id=:companyId AND owner_user_id=:ownerUserId "
                        + "AND lifecycle IN ('DRAFT','ACTIVE') ORDER BY id")
                .param("companyId", companyId).param("ownerUserId", ownerUserId)
                .query(JdbcProjectRepository::map).list();
    }

    private static Project map(ResultSet rs, int row) throws SQLException {
        return new Project(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getString("project_code"),
                rs.getString("name"), rs.getString("description"),
                com.yumpoo.platform.catalog.domain.project.ProjectType.valueOf(rs.getString("project_type")),
                com.yumpoo.platform.catalog.domain.project.ProjectLifecycle.valueOf(rs.getString("lifecycle")),
                rs.getObject("owner_user_id", UUID.class), rs.getString("template_key"),
                rs.getInt("template_version"), rs.getString("customer_name"),
                rs.getString("customer_reference"), rs.getString("delivery_site"),
                rs.getString("contact_note"), rs.getLong("row_version"), instant(rs, "created_at"),
                rs.getObject("created_by_user_id", UUID.class), instant(rs, "updated_at"),
                rs.getObject("updated_by_user_id", UUID.class), nullableInstant(rs, "activated_at"),
                nullableInstant(rs, "archived_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
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
