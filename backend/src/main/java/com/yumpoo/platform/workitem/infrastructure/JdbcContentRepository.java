package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.ContentModels.ContentLocator;
import com.yumpoo.platform.workitem.application.ContentRepository;
import com.yumpoo.platform.workitem.domain.Content;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcContentRepository implements ContentRepository {
    private static final String COLUMNS = """
            id, company_id, project_id, code, name, color_token, sort_order, active,
            protected_content, ever_used, row_version, created_at, created_by_user_id,
            updated_at, updated_by_user_id, deleted_at, deleted_by_user_id
            """;

    private final JdbcClient jdbc;

    public JdbcContentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int insertAll(List<Content> contents) {
        int inserted = 0;
        for (Content content : contents) inserted += insert(content) ? 1 : 0;
        return inserted;
    }

    @Override
    public boolean insert(Content content) {
        JdbcClient.StatementSpec statement = jdbc.sql("""
                INSERT INTO yumpoo.content (
                    id, company_id, project_id, code, name, color_token, sort_order, active,
                    protected_content, ever_used, row_version, created_at, created_by_user_id,
                    updated_at, updated_by_user_id, deleted_at, deleted_by_user_id
                ) VALUES (
                    :id, :companyId, :projectId, :code, :name, :colorToken, :sortOrder, :active,
                    :protectedContent, :everUsed, :rowVersion, :createdAt, :createdByUserId,
                    :updatedAt, :updatedByUserId, :deletedAt, :deletedByUserId
                ) ON CONFLICT (project_id, code) DO NOTHING
                """)
                .param("id", content.id()).param("companyId", content.companyId())
                .param("projectId", content.projectId()).param("code", content.code())
                .param("name", content.name()).param("colorToken", content.colorToken())
                .param("sortOrder", content.sortOrder()).param("active", content.active())
                .param("protectedContent", content.protectedContent()).param("everUsed", content.everUsed())
                .param("rowVersion", content.rowVersion())
                .param("createdAt", at(content.createdAt())).param("createdByUserId", content.createdByUserId())
                .param("updatedAt", at(content.updatedAt())).param("updatedByUserId", content.updatedByUserId());
        statement = nullable(statement, "deletedAt", content.deletedAt() == null ? null : at(content.deletedAt()),
                Types.TIMESTAMP_WITH_TIMEZONE);
        statement = nullable(statement, "deletedByUserId", content.deletedByUserId(), Types.OTHER);
        return statement.update() == 1;
    }

    @Override
    public void initializeCatalog(UUID companyId, UUID projectId, Instant now) {
        jdbc.sql("""
                INSERT INTO yumpoo.content_catalog_version(project_id, company_id, row_version, updated_at)
                VALUES (:projectId, :companyId, 0, :now)
                ON CONFLICT (project_id) DO NOTHING
                """).param("projectId", projectId).param("companyId", companyId)
                .param("now", at(now)).update();
    }

    @Override
    public long catalogVersion(UUID companyId, UUID projectId) {
        return jdbc.sql("""
                SELECT row_version FROM yumpoo.content_catalog_version
                WHERE company_id=:companyId AND project_id=:projectId
                """).param("companyId", companyId).param("projectId", projectId)
                .query(Long.class).optional().orElseThrow();
    }

    @Override
    public long lockCatalogVersion(UUID companyId, UUID projectId) {
        return jdbc.sql("""
                SELECT row_version FROM yumpoo.content_catalog_version
                WHERE company_id=:companyId AND project_id=:projectId FOR UPDATE
                """).param("companyId", companyId).param("projectId", projectId)
                .query(Long.class).optional().orElseThrow();
    }

    @Override
    public boolean bumpCatalogVersion(UUID companyId, UUID projectId, long expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE yumpoo.content_catalog_version
                   SET row_version=row_version+1, updated_at=:now
                 WHERE company_id=:companyId AND project_id=:projectId AND row_version=:expectedVersion
                """).param("now", at(now)).param("companyId", companyId).param("projectId", projectId)
                .param("expectedVersion", expectedVersion).update() == 1;
    }

    @Override
    public int nextSortOrder(UUID companyId, UUID projectId) {
        return jdbc.sql("""
                SELECT COALESCE(max(sort_order), 0) + 10 FROM yumpoo.content
                WHERE company_id=:companyId AND project_id=:projectId AND deleted_at IS NULL
                """).param("companyId", companyId).param("projectId", projectId)
                .query(Integer.class).single();
    }

    @Override
    public long countActive(UUID companyId, UUID projectId, UUID excludingContentId) {
        return jdbc.sql("""
                SELECT count(*) FROM yumpoo.content
                WHERE company_id=:companyId AND project_id=:projectId AND active=true
                  AND deleted_at IS NULL AND id<>:excludingContentId
                """).param("companyId", companyId).param("projectId", projectId)
                .param("excludingContentId", excludingContentId).query(Long.class).single();
    }

    @Override
    public List<Content> findAll(UUID companyId, UUID projectId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM yumpoo.content "
                        + "WHERE company_id=:companyId AND project_id=:projectId AND deleted_at IS NULL "
                        + "ORDER BY sort_order, id")
                .param("companyId", companyId).param("projectId", projectId)
                .query(JdbcContentRepository::map).list();
    }

    @Override
    public Optional<ContentLocator> findLocator(UUID companyId, UUID contentId) {
        return jdbc.sql("""
                SELECT id, project_id FROM yumpoo.content
                WHERE company_id=:companyId AND id=:contentId AND deleted_at IS NULL
                """).param("companyId", companyId).param("contentId", contentId)
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
    public Optional<Content> lockForShare(UUID companyId, UUID projectId, UUID contentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM yumpoo.content "
                        + "WHERE company_id=:companyId AND project_id=:projectId AND id=:contentId "
                        + "AND deleted_at IS NULL FOR SHARE")
                .param("companyId", companyId).param("projectId", projectId)
                .param("contentId", contentId).query(JdbcContentRepository::map).optional();
    }

    @Override
    public boolean hasActiveForTemplate(UUID companyId, UUID projectId, String templateKey,
            int templateVersion) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM yumpoo.content
                 WHERE company_id=:companyId AND project_id=:projectId
                   AND active=true AND deleted_at IS NULL)
                """).param("companyId", companyId).param("projectId", projectId)
                .query(Boolean.class).single();
    }

    @Override
    public Optional<Content> update(Content content, long expectedVersion) {
        JdbcClient.StatementSpec statement = jdbc.sql("""
                UPDATE yumpoo.content
                   SET name=:name, color_token=:colorToken, sort_order=:sortOrder,
                       active=:active, ever_used=:everUsed, row_version=row_version+1,
                       updated_at=:updatedAt, updated_by_user_id=:updatedByUserId,
                       deleted_at=:deletedAt, deleted_by_user_id=:deletedByUserId
                 WHERE company_id=:companyId AND project_id=:projectId AND id=:id
                   AND row_version=:expectedVersion
                RETURNING %s
                """.formatted(COLUMNS))
                .param("name", content.name()).param("colorToken", content.colorToken())
                .param("sortOrder", content.sortOrder()).param("active", content.active())
                .param("everUsed", content.everUsed()).param("updatedAt", at(content.updatedAt()))
                .param("updatedByUserId", content.updatedByUserId()).param("companyId", content.companyId())
                .param("projectId", content.projectId()).param("id", content.id())
                .param("expectedVersion", expectedVersion);
        statement = nullable(statement, "deletedAt", content.deletedAt() == null ? null : at(content.deletedAt()),
                Types.TIMESTAMP_WITH_TIMEZONE);
        statement = nullable(statement, "deletedByUserId", content.deletedByUserId(), Types.OTHER);
        return statement.query(JdbcContentRepository::map).optional();
    }

    @Override
    public void replaceOrder(List<Content> contents) {
        if (contents.isEmpty()) return;
        Content first = contents.getFirst();
        jdbc.sql("""
                UPDATE yumpoo.content SET sort_order=sort_order+1000000
                WHERE company_id=:companyId AND project_id=:projectId AND deleted_at IS NULL
                """).param("companyId", first.companyId()).param("projectId", first.projectId()).update();
        for (Content content : contents) {
            jdbc.sql("""
                    UPDATE yumpoo.content SET sort_order=:sortOrder
                    WHERE company_id=:companyId AND project_id=:projectId AND id=:id
                    """).param("sortOrder", content.sortOrder()).param("companyId", content.companyId())
                    .param("projectId", content.projectId()).param("id", content.id()).update();
        }
    }

    private Optional<Content> find(UUID companyId, UUID projectId, UUID contentId, boolean lock) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM yumpoo.content "
                        + "WHERE company_id=:companyId AND project_id=:projectId AND id=:contentId "
                        + "AND deleted_at IS NULL" + (lock ? " FOR UPDATE" : ""))
                .param("companyId", companyId).param("projectId", projectId).param("contentId", contentId)
                .query(JdbcContentRepository::map).optional();
    }

    private static Content map(ResultSet rs, int row) throws SQLException {
        return new Content(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("color_token"), rs.getInt("sort_order"), rs.getBoolean("active"),
                rs.getBoolean("protected_content"), rs.getBoolean("ever_used"), rs.getLong("row_version"),
                instant(rs, "created_at"), rs.getObject("created_by_user_id", UUID.class),
                instant(rs, "updated_at"), rs.getObject("updated_by_user_id", UUID.class),
                nullableInstant(rs, "deleted_at"), rs.getObject("deleted_by_user_id", UUID.class));
    }

    private static OffsetDateTime at(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static JdbcClient.StatementSpec nullable(JdbcClient.StatementSpec statement,
            String name, Object value, int type) {
        return value == null ? statement.param(name, null, type) : statement.param(name, value);
    }
}
