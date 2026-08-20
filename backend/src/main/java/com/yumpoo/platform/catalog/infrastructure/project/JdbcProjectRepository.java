package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.project.ProjectRepository;
import com.yumpoo.platform.catalog.domain.project.Project;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class JdbcProjectRepository implements ProjectRepository {

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

    private static JdbcClient.StatementSpec nullable(
            JdbcClient.StatementSpec statement,
            String name,
            Object value,
            int sqlType
    ) {
        return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
    }
}
