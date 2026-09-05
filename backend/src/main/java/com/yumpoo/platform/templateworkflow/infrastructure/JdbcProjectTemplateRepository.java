package com.yumpoo.platform.templateworkflow.infrastructure;

import com.yumpoo.platform.templateworkflow.application.ProjectTemplateRepository;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.ContentBlueprint;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.Lifecycle;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.ProjectType;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.RequiredPermission;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.StatusCategory;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.TemplateKey;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.WorkflowStatus;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition.WorkflowTransition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProjectTemplateRepository implements ProjectTemplateRepository {

    private static final String HEADER_SELECT = """
            SELECT id, template_key, template_version, version_code, project_type,
                   display_name, lifecycle_status, row_version, published_at, retired_at
            FROM yumpoo.project_template_definition
            """;

    private final JdbcClient jdbcClient;

    public JdbcProjectTemplateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ProjectTemplateDefinition> find(String templateKey, int version, boolean lock) {
        String sql = HEADER_SELECT + """
                WHERE template_key = :templateKey AND template_version = :version
                """ + (lock ? " FOR UPDATE" : "");
        return jdbcClient.sql(sql)
                .param("templateKey", templateKey)
                .param("version", version)
                .query(this::mapHeader)
                .optional()
                .map(this::loadChildren);
    }

    @Override
    public Optional<ProjectTemplateDefinition> findForShare(String templateKey, int version) {
        return jdbcClient.sql(HEADER_SELECT + """
                        WHERE template_key = :templateKey AND template_version = :version
                        FOR SHARE
                        """)
                .param("templateKey", templateKey)
                .param("version", version)
                .query(this::mapHeader)
                .optional()
                .map(this::loadChildren);
    }

    @Override
    public List<ProjectTemplateDefinition> findPublished() {
        return jdbcClient.sql(HEADER_SELECT + """
                        WHERE lifecycle_status = 'PUBLISHED'
                        ORDER BY CASE template_key
                            WHEN 'RND' THEN 1
                            WHEN 'PRE_SALES' THEN 2
                            WHEN 'IMPLEMENTATION' THEN 3
                            WHEN 'HYPERCARE' THEN 4
                            ELSE 5
                        END, template_version DESC
                        """)
                .query(this::mapHeader)
                .list()
                .stream()
                .map(this::loadChildren)
                .toList();
    }

    @Override
    public boolean publish(UUID id, long expectedRowVersion, UUID actorUserId, Instant changedAt) {
        return jdbcClient.sql("""
                        UPDATE yumpoo.project_template_definition
                           SET lifecycle_status = 'PUBLISHED',
                               row_version = row_version + 1,
                               published_at = :changedAt,
                               published_by_actor_type = 'USER',
                               published_by_user_id = :actorUserId,
                               published_by_system_code = NULL,
                               updated_at = :changedAt
                         WHERE id = :id
                           AND lifecycle_status = 'DRAFT'
                           AND row_version = :expectedRowVersion
                        """)
                .param("changedAt", Timestamp.from(changedAt))
                .param("actorUserId", actorUserId)
                .param("id", id)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public boolean retire(
            UUID id,
            long expectedRowVersion,
            UUID actorUserId,
            String reason,
            Instant changedAt
    ) {
        return jdbcClient.sql("""
                        UPDATE yumpoo.project_template_definition
                           SET lifecycle_status = 'RETIRED',
                               row_version = row_version + 1,
                               retired_at = :changedAt,
                               retired_by_user_id = :actorUserId,
                               retire_reason = :reason,
                               updated_at = :changedAt
                         WHERE id = :id
                           AND lifecycle_status = 'PUBLISHED'
                           AND row_version = :expectedRowVersion
                        """)
                .param("changedAt", Timestamp.from(changedAt))
                .param("actorUserId", actorUserId)
                .param("reason", reason)
                .param("id", id)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    private Header mapHeader(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Header(
                resultSet.getObject("id", UUID.class),
                TemplateKey.valueOf(resultSet.getString("template_key")),
                resultSet.getInt("template_version"),
                resultSet.getString("version_code"),
                ProjectType.valueOf(resultSet.getString("project_type")),
                resultSet.getString("display_name"),
                Lifecycle.valueOf(resultSet.getString("lifecycle_status")),
                resultSet.getLong("row_version"),
                nullableInstant(resultSet, "published_at"),
                nullableInstant(resultSet, "retired_at")
        );
    }

    private ProjectTemplateDefinition loadChildren(Header header) {
        List<ContentBlueprint> blueprints = jdbcClient.sql("""
                        SELECT content_code, display_name, color_token, sort_order
                        FROM yumpoo.project_template_content_blueprint
                        WHERE template_id = :templateId
                        ORDER BY sort_order
                        """)
                .param("templateId", header.id())
                .query((resultSet, rowNumber) -> new ContentBlueprint(
                        resultSet.getString("content_code"), resultSet.getString("display_name"),
                        resultSet.getString("color_token"),
                        resultSet.getInt("sort_order")))
                .list();
        List<WorkflowStatus> statuses = jdbcClient.sql("""
                        SELECT status_code, display_name, status_category, sort_order, is_initial, is_terminal
                        FROM yumpoo.workflow_status_definition
                        WHERE template_id = :templateId
                        ORDER BY sort_order
                        """)
                .param("templateId", header.id())
                .query((resultSet, rowNumber) -> new WorkflowStatus(
                        resultSet.getString("status_code"), resultSet.getString("display_name"),
                        StatusCategory.valueOf(resultSet.getString("status_category")),
                        resultSet.getInt("sort_order"), resultSet.getBoolean("is_initial"),
                        resultSet.getBoolean("is_terminal")))
                .list();
        List<WorkflowTransition> transitions = jdbcClient.sql("""
                        SELECT from_status, to_status, required_permission, requires_resolution
                        FROM yumpoo.workflow_transition_definition
                        WHERE template_id = :templateId
                        ORDER BY from_status, to_status
                        """)
                .param("templateId", header.id())
                .query((resultSet, rowNumber) -> new WorkflowTransition(
                        resultSet.getString("from_status"), resultSet.getString("to_status"),
                        RequiredPermission.valueOf(resultSet.getString("required_permission")),
                        resultSet.getBoolean("requires_resolution")))
                .list();
        return new ProjectTemplateDefinition(
                header.id(), header.templateKey(), header.version(), header.versionCode(),
                header.projectType(), header.displayName(), header.lifecycle(), header.rowVersion(),
                header.publishedAt(), header.retiredAt(), blueprints, statuses, transitions);
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record Header(
            UUID id,
            TemplateKey templateKey,
            int version,
            String versionCode,
            ProjectType projectType,
            String displayName,
            Lifecycle lifecycle,
            long rowVersion,
            Instant publishedAt,
            Instant retiredAt
    ) {
    }
}
