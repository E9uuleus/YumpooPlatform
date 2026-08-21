package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.ContentRepository;
import com.yumpoo.platform.workitem.domain.Content;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcContentRepository implements ContentRepository {

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
            )
            """;

    private final JdbcClient jdbcClient;

    public JdbcContentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public int insertAll(List<Content> contents) {
        int inserted = 0;
        for (Content content : contents) {
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
            inserted += statement.update();
        }
        return inserted;
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

    private static JdbcClient.StatementSpec nullable(
            JdbcClient.StatementSpec statement,
            String name,
            Object value,
            int sqlType
    ) {
        return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
    }
}
