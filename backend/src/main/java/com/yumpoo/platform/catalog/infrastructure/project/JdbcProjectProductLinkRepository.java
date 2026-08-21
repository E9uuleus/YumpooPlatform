package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.project.ProjectProductLinkModels.LinkProjection;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkModels.ProductCandidate;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkModels.ProductCandidatePage;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkRepository;
import com.yumpoo.platform.catalog.domain.project.ProjectProductLink;
import com.yumpoo.platform.catalog.domain.project.ProjectProductRelationType;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
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
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcProjectProductLinkRepository implements ProjectProductLinkRepository {

    private static final String LINK_COLUMNS = """
            id AS link_id, company_id, project_id, product_id, relation_type, is_primary,
            linked_at, linked_by_user_id, updated_at, updated_by_user_id,
            removed_at, removed_by_user_id, remove_reason, row_version
            """;

    private static final String INSERT = """
            INSERT INTO yumpoo.project_product_link (
                id, company_id, project_id, product_id, relation_type, is_primary,
                linked_at, linked_by_user_id, updated_at, updated_by_user_id,
                removed_at, removed_by_user_id, remove_reason, row_version
            ) VALUES (
                :id, :companyId, :projectId, :productId, :relationType, :isPrimary,
                :linkedAt, :linkedByUserId, :updatedAt, :updatedByUserId,
                NULL, NULL, NULL, :rowVersion
            ) ON CONFLICT DO NOTHING
            """;

    private final JdbcClient jdbcClient;

    public JdbcProjectProductLinkRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean insert(ProjectProductLink link) {
        return jdbcClient.sql(INSERT)
                .param("id", link.id())
                .param("companyId", link.companyId())
                .param("projectId", link.projectId())
                .param("productId", link.productId())
                .param("relationType", link.relationType().name())
                .param("isPrimary", link.primary())
                .param("linkedAt", utc(link.linkedAt()))
                .param("linkedByUserId", link.linkedByUserId())
                .param("updatedAt", utc(link.updatedAt()))
                .param("updatedByUserId", link.updatedByUserId())
                .param("rowVersion", link.rowVersion())
                .update() == 1;
    }

    @Override
    public Optional<ProjectProductLink> lock(UUID companyId, UUID projectId, UUID linkId) {
        return jdbcClient.sql("SELECT " + LINK_COLUMNS + " FROM yumpoo.project_product_link "
                        + "WHERE company_id=:companyId AND project_id=:projectId AND id=:linkId FOR UPDATE")
                .param("companyId", companyId).param("projectId", projectId).param("linkId", linkId)
                .query(JdbcProjectProductLinkRepository::mapLink).optional();
    }

    @Override
    public Optional<ProjectProductLink> update(ProjectProductLink link, long expectedVersion) {
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                UPDATE yumpoo.project_product_link
                   SET is_primary=:isPrimary, updated_at=:updatedAt,
                       updated_by_user_id=:updatedByUserId, removed_at=:removedAt,
                       removed_by_user_id=:removedByUserId, remove_reason=:removeReason,
                       row_version=row_version+1
                 WHERE company_id=:companyId AND project_id=:projectId AND id=:linkId
                   AND row_version=:expectedVersion
                RETURNING %s
                """.formatted(LINK_COLUMNS))
                .param("isPrimary", link.primary())
                .param("updatedAt", utc(link.updatedAt()))
                .param("updatedByUserId", link.updatedByUserId());
        statement = nullable(statement, "removedAt",
                link.removedAt() == null ? null : utc(link.removedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
        statement = nullable(statement, "removedByUserId", link.removedByUserId(), Types.OTHER);
        statement = nullable(statement, "removeReason", link.removeReason(), Types.VARCHAR);
        return statement.param("companyId", link.companyId()).param("projectId", link.projectId())
                .param("linkId", link.id()).param("expectedVersion", expectedVersion)
                .query(JdbcProjectProductLinkRepository::mapLink).optional();
    }

    @Override
    public Optional<ProjectProductLink> findActivePrimary(UUID companyId, UUID projectId) {
        return jdbcClient.sql("SELECT " + LINK_COLUMNS + " FROM yumpoo.project_product_link "
                        + "WHERE company_id=:companyId AND project_id=:projectId "
                        + "AND removed_at IS NULL AND is_primary FOR UPDATE")
                .param("companyId", companyId).param("projectId", projectId)
                .query(JdbcProjectProductLinkRepository::mapLink).optional();
    }

    @Override
    public List<LinkProjection> findActiveViews(UUID companyId, UUID projectId) {
        return jdbcClient.sql(viewSelect() + " WHERE l.company_id=:companyId AND l.project_id=:projectId "
                        + "AND l.removed_at IS NULL "
                        + "ORDER BY l.is_primary DESC, p.name, p.product_code, l.relation_type, l.id")
                .param("companyId", companyId).param("projectId", projectId)
                .query(JdbcProjectProductLinkRepository::mapProjection).list();
    }

    @Override
    public Optional<LinkProjection> findView(UUID companyId, UUID projectId, UUID linkId) {
        return jdbcClient.sql(viewSelect() + " WHERE l.company_id=:companyId AND l.project_id=:projectId "
                        + "AND l.id=:linkId")
                .param("companyId", companyId).param("projectId", projectId).param("linkId", linkId)
                .query(JdbcProjectProductLinkRepository::mapProjection).optional();
    }

    @Override
    public ProductCandidatePage findCandidates(UUID companyId, UUID projectId, String query,
                                               OffsetPageRequest page) {
        String predicate = """
                WHERE p.company_id=:companyId AND p.status='ACTIVE'
                  AND (p.product_code LIKE :codePrefix ESCAPE '\\'
                       OR lower(p.name) LIKE :namePrefix ESCAPE '\\')
                """;
        List<ProductCandidate> items = jdbcClient.sql("""
                SELECT p.id, p.product_code, p.name,
                       COALESCE(string_agg(l.relation_type, ',' ORDER BY l.relation_type)
                           FILTER (WHERE l.id IS NOT NULL), '') AS relation_types,
                       COALESCE(bool_or(l.is_primary) FILTER (WHERE l.id IS NOT NULL), false) AS primary_link
                  FROM yumpoo.product p
                  LEFT JOIN yumpoo.project_product_link l
                    ON l.company_id=p.company_id AND l.product_id=p.id
                   AND l.project_id=:projectId AND l.removed_at IS NULL
                """ + predicate + " GROUP BY p.id, p.product_code, p.name "
                        + "ORDER BY p.name, p.product_code, p.id LIMIT :limit OFFSET :offset")
                .param("companyId", companyId).param("projectId", projectId)
                .param("codePrefix", prefix(query.toUpperCase(java.util.Locale.ROOT)))
                .param("namePrefix", prefix(query.toLowerCase(java.util.Locale.ROOT)))
                .param("limit", page.size()).param("offset", (long) page.page() * page.size())
                .query((rs, row) -> new ProductCandidate(rs.getObject("id", UUID.class),
                        rs.getString("product_code"), rs.getString("name"),
                        split(rs.getString("relation_types")), rs.getBoolean("primary_link"))).list();
        long total = jdbcClient.sql("SELECT count(*) FROM yumpoo.product p " + predicate)
                .param("companyId", companyId)
                .param("codePrefix", prefix(query.toUpperCase(java.util.Locale.ROOT)))
                .param("namePrefix", prefix(query.toLowerCase(java.util.Locale.ROOT)))
                .query(Long.class).single();
        return new ProductCandidatePage(items, page.page(), page.size(), total,
                total == 0 ? 0 : (int) ((total + page.size() - 1) / page.size()));
    }

    @Override
    public boolean hasActiveRelation(UUID companyId, UUID projectId, UUID productId,
                                     Set<ProjectProductRelationType> allowedTypes) {
        if (allowedTypes.isEmpty()) return false;
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM yumpoo.project_product_link
                     WHERE company_id=:companyId AND project_id=:projectId AND product_id=:productId
                       AND removed_at IS NULL AND relation_type IN (:relationTypes)
                )
                """).param("companyId", companyId).param("projectId", projectId)
                .param("productId", productId)
                .param("relationTypes", allowedTypes.stream().map(Enum::name).toList())
                .query(Boolean.class).single();
    }

    private static String viewSelect() {
        return "SELECT l.id AS link_id, l.company_id, l.project_id, l.product_id, "
                + "l.relation_type, l.is_primary, l.linked_at, l.linked_by_user_id, "
                + "l.updated_at, l.updated_by_user_id, l.removed_at, l.removed_by_user_id, "
                + "l.remove_reason, l.row_version, p.product_code, p.name AS product_name, "
                + "p.status AS product_status FROM yumpoo.project_product_link l "
                + "JOIN yumpoo.product p ON p.id=l.product_id AND p.company_id=l.company_id";
    }

    private static LinkProjection mapProjection(ResultSet rs, int row) throws SQLException {
        return new LinkProjection(mapLink(rs, row), rs.getString("product_code"),
                rs.getString("product_name"), rs.getString("product_status"));
    }

    private static ProjectProductLink mapLink(ResultSet rs, int row) throws SQLException {
        return new ProjectProductLink(rs.getObject("link_id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                ProjectProductRelationType.valueOf(rs.getString("relation_type")),
                rs.getBoolean("is_primary"), instant(rs, "linked_at"),
                rs.getObject("linked_by_user_id", UUID.class), instant(rs, "updated_at"),
                rs.getObject("updated_by_user_id", UUID.class), nullableInstant(rs, "removed_at"),
                rs.getObject("removed_by_user_id", UUID.class), rs.getString("remove_reason"),
                rs.getLong("row_version"));
    }

    private static String prefix(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static List<String> split(String value) {
        return value == null || value.isEmpty() ? List.of() : Arrays.asList(value.split(","));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static JdbcClient.StatementSpec nullable(
            JdbcClient.StatementSpec statement, String name, Object value, int sqlType) {
        return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
    }
}
