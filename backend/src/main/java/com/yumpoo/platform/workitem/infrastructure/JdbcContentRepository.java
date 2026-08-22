package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.ContentRepository;
import com.yumpoo.platform.workitem.application.ContentModels.ContentLocator;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.ContentStatus;
import com.yumpoo.platform.workitem.domain.ContentViewType;
import com.yumpoo.platform.workitem.domain.ContentWorkItemType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcContentRepository implements ContentRepository {

    private static final String COLUMNS = """
            id, company_id, project_id, code, name, description, work_item_type, status,
            default_view_type, view_config::text AS view_config, applied_template_key,
            applied_template_version, applied_blueprint_code, row_version, created_at,
            created_by_user_id, updated_at, updated_by_user_id, archived_at, archived_by_user_id
            """;

    private static final String INSERT = """
            INSERT INTO yumpoo.content (
                id, company_id, project_id, code, name, description, work_item_type,
                status, default_view_type, view_config, applied_template_key,
                applied_template_version, applied_blueprint_code, row_version,
                created_at, created_by_user_id, updated_at, updated_by_user_id,
                archived_at, archived_by_user_id
            ) VALUES (
                :id, :companyId, :projectId, :code, :name, :description, :workItemType,
                :status, :defaultViewType, CAST(:viewConfig AS jsonb), :templateKey,
                :templateVersion, :blueprintCode, :rowVersion,
                :createdAt, :createdByUserId, :updatedAt, :updatedByUserId,
                :archivedAt, :archivedByUserId
            ) ON CONFLICT (project_id, code) DO NOTHING
            """;

    private final JdbcClient jdbcClient;

    public JdbcContentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public int insertAll(List<Content> contents) {
        int inserted = 0;
        for (Content content : contents) {
            inserted += insert(content) ? 1 : 0;
        }
        return inserted;
    }

    @Override
    public boolean insert(Content content) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(INSERT)
                    .param("id", content.id())
                    .param("companyId", content.companyId())
                    .param("projectId", content.projectId())
                    .param("code", content.code())
                    .param("name", content.name())
                    .param("workItemType", content.workItemType().name())
                    .param("status", content.status().name())
                    .param("defaultViewType", content.defaultViewType().name())
                    .param("viewConfig", content.viewConfigJson())
                    .param("templateKey", content.appliedTemplateKey())
                    .param("templateVersion", content.appliedTemplateVersion())
                    .param("blueprintCode", content.appliedBlueprintCode())
                    .param("rowVersion", content.rowVersion())
                    .param("createdAt", OffsetDateTime.ofInstant(content.createdAt(), ZoneOffset.UTC))
                    .param("createdByUserId", content.createdByUserId())
                    .param("updatedAt", OffsetDateTime.ofInstant(content.updatedAt(), ZoneOffset.UTC))
                    .param("updatedByUserId", content.updatedByUserId());
            statement = nullable(statement, "description", content.description(), Types.VARCHAR);
            statement = nullable(statement, "archivedAt", content.archivedAt() == null
                    ? null : OffsetDateTime.ofInstant(content.archivedAt(), ZoneOffset.UTC),
                    Types.TIMESTAMP_WITH_TIMEZONE);
            statement = nullable(statement, "archivedByUserId", content.archivedByUserId(), Types.OTHER);
        return statement.update() == 1;
    }

    @Override
    public List<Content> findAll(UUID companyId, UUID projectId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM yumpoo.content "
                        + "WHERE company_id=:companyId AND project_id=:projectId "
                        + "ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END, name, code, id")
                .param("companyId", companyId).param("projectId", projectId)
                .query(JdbcContentRepository::map).list();
    }

    @Override
    public Optional<ContentLocator> findLocator(UUID companyId, UUID contentId) {
        return jdbcClient.sql("SELECT id, project_id FROM yumpoo.content "
                        + "WHERE company_id=:companyId AND id=:contentId")
                .param("companyId", companyId).param("contentId", contentId)
                .query((rs, row) -> new ContentLocator(rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class))).optional();
    }

    @Override
    public Optional<Content> find(UUID companyId, UUID projectId, UUID contentId) {
        return find(companyId, projectId, contentId, false);
    }

    @Override
    public Optional<Content> lock(UUID companyId, UUID projectId, UUID contentId) {
        return find(companyId, projectId, contentId, true);
    }

    @Override
    public Optional<Content> update(Content content, long expectedVersion) {
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                UPDATE yumpoo.content SET name=:name, description=:description,
                    status=:status, default_view_type=:defaultViewType,
                    view_config=CAST(:viewConfig AS jsonb), row_version=row_version+1,
                    updated_at=:updatedAt, updated_by_user_id=:updatedByUserId,
                    archived_at=:archivedAt, archived_by_user_id=:archivedByUserId
                WHERE company_id=:companyId AND project_id=:projectId AND id=:id
                  AND row_version=:expectedVersion
                RETURNING %s
                """.formatted(COLUMNS))
                .param("name", content.name()).param("status", content.status().name())
                .param("defaultViewType", content.defaultViewType().name())
                .param("viewConfig", content.viewConfigJson())
                .param("updatedAt", OffsetDateTime.ofInstant(content.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", content.updatedByUserId())
                .param("companyId", content.companyId()).param("projectId", content.projectId())
                .param("id", content.id()).param("expectedVersion", expectedVersion);
        statement = nullable(statement, "description", content.description(), Types.VARCHAR);
        statement = nullable(statement, "archivedAt", content.archivedAt() == null ? null
                : OffsetDateTime.ofInstant(content.archivedAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        statement = nullable(statement, "archivedByUserId", content.archivedByUserId(), Types.OTHER);
        return statement.query(JdbcContentRepository::map).optional();
    }

    @Override
    public boolean hasActiveForTemplate(UUID companyId, UUID projectId, String templateKey,
                                        int templateVersion) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM yumpoo.content
                    WHERE company_id=:companyId AND project_id=:projectId AND status='ACTIVE'
                      AND applied_template_key=:templateKey
                      AND applied_template_version=:templateVersion
                )
                """).param("companyId", companyId).param("projectId", projectId)
                .param("templateKey", templateKey).param("templateVersion", templateVersion)
                .query(Boolean.class).single();
    }

    private Optional<Content> find(UUID companyId, UUID projectId, UUID contentId, boolean lock) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM yumpoo.content "
                        + "WHERE company_id=:companyId AND project_id=:projectId AND id=:contentId"
                        + (lock ? " FOR UPDATE" : ""))
                .param("companyId", companyId).param("projectId", projectId)
                .param("contentId", contentId).query(JdbcContentRepository::map).optional();
    }

    private static Content map(ResultSet rs, int row) throws SQLException {
        return new Content(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), ContentWorkItemType.valueOf(rs.getString("work_item_type")),
                ContentStatus.valueOf(rs.getString("status")),
                ContentViewType.valueOf(rs.getString("default_view_type")), rs.getString("view_config"),
                rs.getString("applied_template_key"), rs.getInt("applied_template_version"),
                rs.getString("applied_blueprint_code"), rs.getLong("row_version"),
                instant(rs, "created_at"), rs.getObject("created_by_user_id", UUID.class),
                instant(rs, "updated_at"), rs.getObject("updated_by_user_id", UUID.class),
                nullableInstant(rs, "archived_at"), rs.getObject("archived_by_user_id", UUID.class));
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
