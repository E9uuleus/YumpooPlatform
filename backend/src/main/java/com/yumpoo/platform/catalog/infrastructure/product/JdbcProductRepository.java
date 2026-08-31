package com.yumpoo.platform.catalog.infrastructure.product;

import com.yumpoo.platform.catalog.application.product.ProductListStatus;
import com.yumpoo.platform.catalog.application.product.ProductPageResult;
import com.yumpoo.platform.catalog.application.product.ProductRepository;
import com.yumpoo.platform.catalog.domain.product.Product;
import com.yumpoo.platform.catalog.domain.product.ProductStatus;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
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
public class JdbcProductRepository implements ProductRepository {

    private static final String COLUMNS = """
            id, company_id, product_code, name, description, status, owner_user_id,
            row_version, created_at, created_by_user_id, updated_at, updated_by_user_id,
            archived_at, archived_by_user_id
            """;

    private static final String VISIBLE_PREDICATE = """
            p.company_id = :companyId
              AND (:administrator OR p.owner_user_id = :actorUserId OR EXISTS (
                    SELECT 1
                    FROM yumpoo.project_product_link ppl
                    JOIN yumpoo.project_membership pm
                      ON pm.company_id = ppl.company_id
                     AND pm.project_id = ppl.project_id
                     AND pm.user_id = :actorUserId
                     AND pm.status = 'ACTIVE'
                    WHERE ppl.company_id = p.company_id
                      AND ppl.product_id = p.id
                      AND ppl.removed_at IS NULL
              ))
            """;

    private static final String STATUS_PREDICATE = """
              AND ((:includeActive AND p.status = 'ACTIVE')
                   OR (:includeArchived AND p.status = 'ARCHIVED'))
            """;

    private static final String SEARCH_PREDICATE = """
              AND (:allProducts OR p.product_code LIKE :codePrefix ESCAPE '\\'
                   OR lower(p.name) LIKE :namePrefix ESCAPE '\\')
            """;

    private static final String FIND_BY_ID = "SELECT " + COLUMNS + " FROM yumpoo.product "
            + "WHERE company_id = :companyId AND id = :productId";

    private static final String INSERT = """
            INSERT INTO yumpoo.product (
                id, company_id, product_code, name, description, status, owner_user_id,
                row_version, created_at, created_by_user_id, updated_at, updated_by_user_id,
                archived_at, archived_by_user_id
            ) VALUES (
                :id, :companyId, :code, :name, :description, :status, :ownerUserId,
                :rowVersion, :createdAt, :createdByUserId, :updatedAt, :updatedByUserId,
                :archivedAt, :archivedByUserId
            ) ON CONFLICT (company_id, product_code) DO NOTHING
            """;

    private static final String UPDATE_DETAILS = """
            UPDATE yumpoo.product
            SET name = :name,
                description = :description,
                row_version = row_version + 1,
                updated_at = :updatedAt,
                updated_by_user_id = :updatedByUserId
            WHERE company_id = :companyId
              AND id = :id
              AND status = 'ACTIVE'
              AND row_version = :expectedRowVersion
            RETURNING
            """ + COLUMNS;

    private static final String CHANGE_STATUS = """
            UPDATE yumpoo.product
            SET status = :newStatus,
                row_version = row_version + 1,
                updated_at = :updatedAt,
                updated_by_user_id = :updatedByUserId,
                archived_at = :archivedAt,
                archived_by_user_id = :archivedByUserId
            WHERE company_id = :companyId
              AND id = :id
              AND status = :expectedStatus
              AND row_version = :expectedRowVersion
            RETURNING
            """ + COLUMNS;

    private static final String REASSIGN_OWNER = """
            UPDATE yumpoo.product
            SET owner_user_id = :ownerUserId,
                row_version = row_version + 1,
                updated_at = :updatedAt,
                updated_by_user_id = :updatedByUserId
            WHERE company_id = :companyId
              AND id = :id
              AND row_version = :expectedRowVersion
            RETURNING
            """ + COLUMNS;

    private final JdbcClient jdbcClient;

    public JdbcProductRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ProductPageResult findVisible(
            CurrentActor actor,
            ProductListStatus status,
            String query,
            OffsetPageRequest page
    ) {
        boolean administrator = actor.hasRole(PlatformRoleCode.COMPANY_ADMIN);
        long total = bindVisibility(jdbcClient.sql("SELECT count(*) FROM yumpoo.product p WHERE "
                        + VISIBLE_PREDICATE + STATUS_PREDICATE + SEARCH_PREDICATE), actor,
                        administrator, status, query)
                .query(Long.class).single();
        List<Product> items = bindVisibility(jdbcClient.sql("SELECT p.*"
                                + " FROM yumpoo.product p WHERE " + VISIBLE_PREDICATE
                                + STATUS_PREDICATE + SEARCH_PREDICATE
                                + " ORDER BY p.name, p.product_code, p.id LIMIT :limit OFFSET :offset"),
                        actor, administrator, status, query)
                .param("limit", page.size())
                .param("offset", (long) page.page() * page.size())
                .query(JdbcProductRepository::map)
                .list();
        return new ProductPageResult(items, total);
    }

    @Override
    public Optional<Product> findVisibleById(CurrentActor actor, UUID productId) {
        return jdbcClient.sql("SELECT p.* FROM yumpoo.product p WHERE "
                        + VISIBLE_PREDICATE + " AND id = :productId")
                .param("companyId", actor.companyId())
                .param("administrator", actor.hasRole(PlatformRoleCode.COMPANY_ADMIN))
                .param("actorUserId", actor.userId())
                .param("productId", productId)
                .query(JdbcProductRepository::map)
                .optional();
    }

    @Override
    public Optional<Product> findById(UUID companyId, UUID productId) {
        return jdbcClient.sql(FIND_BY_ID)
                .param("companyId", companyId)
                .param("productId", productId)
                .query(JdbcProductRepository::map)
                .optional();
    }

    @Override
    public Optional<Product> lockById(UUID companyId, UUID productId) {
        return locked(companyId, productId, " FOR UPDATE");
    }

    @Override
    public Optional<Product> lockByIdForShare(UUID companyId, UUID productId) {
        return locked(companyId, productId, " FOR SHARE");
    }

    private Optional<Product> locked(UUID companyId, UUID productId, String lockClause) {
        return jdbcClient.sql(FIND_BY_ID + lockClause)
                .param("companyId", companyId)
                .param("productId", productId)
                .query(JdbcProductRepository::map)
                .optional();
    }

    @Override
    public List<Product> findByOwner(UUID companyId, UUID ownerUserId, ProductStatus status) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM yumpoo.product "
                        + "WHERE company_id = :companyId AND owner_user_id = :ownerUserId "
                        + "AND status = :status ORDER BY id")
                .param("companyId", companyId)
                .param("ownerUserId", ownerUserId)
                .param("status", status.name())
                .query(JdbcProductRepository::map)
                .list();
    }

    @Override
    public boolean insert(Product product) {
        return bind(jdbcClient.sql(INSERT), product).update() == 1;
    }

    @Override
    public Optional<Product> updateDetails(Product product, long expectedRowVersion) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(UPDATE_DETAILS)
                .param("name", product.name());
        statement = nullable(statement, "description", product.description(), Types.VARCHAR);
        return statement
                .param("updatedAt", utc(product.updatedAt()))
                .param("updatedByUserId", product.updatedByUserId())
                .param("companyId", product.companyId())
                .param("id", product.id())
                .param("expectedRowVersion", expectedRowVersion)
                .query(JdbcProductRepository::map)
                .optional();
    }

    @Override
    public Optional<Product> changeStatus(
            Product product,
            ProductStatus expectedStatus,
            long expectedRowVersion
    ) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(CHANGE_STATUS)
                .param("newStatus", product.status().name())
                .param("updatedAt", utc(product.updatedAt()))
                .param("updatedByUserId", product.updatedByUserId());
        statement = nullable(statement, "archivedAt", product.archivedAt() == null
                ? null : utc(product.archivedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
        statement = nullable(statement, "archivedByUserId", product.archivedByUserId(), Types.OTHER);
        return statement
                .param("companyId", product.companyId())
                .param("id", product.id())
                .param("expectedStatus", expectedStatus.name())
                .param("expectedRowVersion", expectedRowVersion)
                .query(JdbcProductRepository::map)
                .optional();
    }

    @Override
    public Optional<Product> reassignOwner(Product product, long expectedRowVersion) {
        return jdbcClient.sql(REASSIGN_OWNER)
                .param("ownerUserId", product.ownerUserId())
                .param("updatedAt", utc(product.updatedAt()))
                .param("updatedByUserId", product.updatedByUserId())
                .param("companyId", product.companyId())
                .param("id", product.id())
                .param("expectedRowVersion", expectedRowVersion)
                .query(JdbcProductRepository::map)
                .optional();
    }

    private static JdbcClient.StatementSpec bindVisibility(
            JdbcClient.StatementSpec statement,
            CurrentActor actor,
            boolean administrator,
            ProductListStatus status,
            String query
    ) {
        String escaped = query == null ? "" : escapeLike(query);
        return statement
                .param("companyId", actor.companyId())
                .param("administrator", administrator)
                .param("actorUserId", actor.userId())
                .param("includeActive", status.includeActive())
                .param("includeArchived", status.includeArchived())
                .param("allProducts", query == null)
                .param("codePrefix", escaped.toUpperCase(java.util.Locale.ROOT) + "%")
                .param("namePrefix", escaped.toLowerCase(java.util.Locale.ROOT) + "%");
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Product product) {
        statement = statement
                .param("id", product.id())
                .param("companyId", product.companyId())
                .param("code", product.code())
                .param("name", product.name())
                .param("status", product.status().name())
                .param("ownerUserId", product.ownerUserId())
                .param("rowVersion", product.rowVersion())
                .param("createdAt", utc(product.createdAt()))
                .param("createdByUserId", product.createdByUserId())
                .param("updatedAt", utc(product.updatedAt()))
                .param("updatedByUserId", product.updatedByUserId());
        statement = nullable(statement, "description", product.description(), Types.VARCHAR);
        statement = nullable(statement, "archivedAt", product.archivedAt() == null
                ? null : utc(product.archivedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
        return nullable(statement, "archivedByUserId", product.archivedByUserId(), Types.OTHER);
    }

    private static JdbcClient.StatementSpec nullable(
            JdbcClient.StatementSpec statement,
            String name,
            Object value,
            int sqlType
    ) {
        return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
    }

    private static Product map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Product(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("company_id", UUID.class),
                resultSet.getString("product_code"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                ProductStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("owner_user_id", UUID.class),
                resultSet.getLong("row_version"),
                instant(resultSet, "created_at"),
                resultSet.getObject("created_by_user_id", UUID.class),
                instant(resultSet, "updated_at"),
                resultSet.getObject("updated_by_user_id", UUID.class),
                nullableInstant(resultSet, "archived_at"),
                resultSet.getObject("archived_by_user_id", UUID.class));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException(column + " must not be null");
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
