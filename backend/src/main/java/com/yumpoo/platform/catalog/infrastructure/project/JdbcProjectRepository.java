package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.project.ProjectRepository;
import com.yumpoo.platform.catalog.domain.project.Project;
import com.yumpoo.platform.catalog.domain.project.ProjectType;
import com.yumpoo.platform.catalog.application.project.ProjectLifecycleFilter;
import com.yumpoo.platform.catalog.application.project.ProjectMembershipModels;
import com.yumpoo.platform.catalog.application.project.ProjectPageResult;
import com.yumpoo.platform.catalog.application.project.ProjectQueryRow;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
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
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

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

    private static final String VISIBLE_FROM = """
            FROM yumpoo.project p
            JOIN yumpoo.workspace w ON w.id = p.workspace_id AND w.company_id = p.company_id
            LEFT JOIN yumpoo.project_membership m
              ON m.project_id = p.id AND m.company_id = p.company_id
             AND m.user_id = :actorUserId AND m.status = 'ACTIVE'
            WHERE p.company_id = :companyId AND (:admin OR m.id IS NOT NULL)
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

    @Override
    public Optional<Project> updateDetails(Project project, long expectedVersion) {
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                UPDATE yumpoo.project SET name=:name, description=:description,
                    customer_name=:customerName, customer_reference=:customerReference,
                    delivery_site=:deliverySite, contact_note=:contactNote,
                    row_version=row_version+1, updated_at=:updatedAt,
                    updated_by_user_id=:updatedByUserId
                WHERE company_id=:companyId AND id=:id AND lifecycle <> 'ARCHIVED'
                  AND row_version=:expectedVersion
                RETURNING %s
                """.formatted(COLUMNS))
                .param("name", project.name())
                .param("updatedAt", OffsetDateTime.ofInstant(project.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", project.updatedByUserId())
                .param("companyId", project.companyId()).param("id", project.id())
                .param("expectedVersion", expectedVersion);
        statement = nullable(statement, "description", project.description(), Types.VARCHAR);
        statement = nullable(statement, "customerName", project.customerName(), Types.VARCHAR);
        statement = nullable(statement, "customerReference", project.customerReference(), Types.VARCHAR);
        statement = nullable(statement, "deliverySite", project.deliverySite(), Types.VARCHAR);
        statement = nullable(statement, "contactNote", project.contactNote(), Types.VARCHAR);
        return statement.query(JdbcProjectRepository::map).optional();
    }

    @Override
    public Optional<Project> activate(Project project, long expectedVersion) {
        return jdbcClient.sql("""
                UPDATE yumpoo.project SET lifecycle='ACTIVE', activated_at=:activatedAt,
                    row_version=row_version+1, updated_at=:updatedAt,
                    updated_by_user_id=:updatedByUserId
                WHERE company_id=:companyId AND id=:id AND lifecycle='DRAFT'
                  AND row_version=:expectedVersion
                RETURNING %s
                """.formatted(COLUMNS))
                .param("activatedAt", OffsetDateTime.ofInstant(project.activatedAt(), ZoneOffset.UTC))
                .param("updatedAt", OffsetDateTime.ofInstant(project.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", project.updatedByUserId())
                .param("companyId", project.companyId()).param("id", project.id())
                .param("expectedVersion", expectedVersion)
                .query(JdbcProjectRepository::map).optional();
    }

    @Override
    public Optional<ProjectQueryRow> findVisibleById(CurrentActor actor, UUID projectId) {
        return visible(jdbcClient.sql("SELECT p.*, w.code AS workspace_code, "
                        + "w.name AS workspace_name, "
                        + "CASE WHEN p.owner_user_id=:actorUserId THEN 'OWNER' "
                        + "WHEN m.id IS NOT NULL THEN 'MEMBER' ELSE 'COMPANY_ADMIN_READ_ONLY' END actor_access "
                        + VISIBLE_FROM + " AND p.id=:projectId"), actor)
                .param("projectId", projectId)
                .query(JdbcProjectRepository::mapQueryRow).optional();
    }

    @Override
    public ProjectPageResult findVisible(CurrentActor actor, UUID workspaceId,
                                         ProjectType projectType, ProjectLifecycleFilter lifecycle,
                                         UUID productId,
                                         OffsetPageRequest page) {
        String predicate = " AND (:allWorkspaces OR p.workspace_id=:workspaceId) "
                + "AND (:allTypes OR p.project_type=:projectType) "
                + "AND (:allProducts OR EXISTS (SELECT 1 FROM yumpoo.project_product_link ppl "
                + "WHERE ppl.company_id=p.company_id AND ppl.project_id=p.id "
                + "AND ppl.product_id=:productId AND ppl.removed_at IS NULL)) "
                + "AND ((:draft AND p.lifecycle='DRAFT') OR (:active AND p.lifecycle='ACTIVE') "
                + "OR (:archived AND p.lifecycle='ARCHIVED')) ";
        JdbcClient.StatementSpec items = visible(jdbcClient.sql("SELECT p.*, w.code AS workspace_code, "
                + "w.name AS workspace_name, CASE WHEN p.owner_user_id=:actorUserId THEN 'OWNER' "
                + "WHEN m.id IS NOT NULL THEN 'MEMBER' ELSE 'COMPANY_ADMIN_READ_ONLY' END actor_access "
                + VISIBLE_FROM + predicate
                + "ORDER BY p.name, p.project_code, p.id LIMIT :limit OFFSET :offset"), actor);
        items = filters(items, workspaceId, projectType, lifecycle, productId)
                .param("limit", page.size()).param("offset", (long) page.page() * page.size());
        JdbcClient.StatementSpec count = filters(visible(jdbcClient.sql(
                "SELECT count(*) " + VISIBLE_FROM + predicate), actor),
                workspaceId, projectType, lifecycle, productId);
        return new ProjectPageResult(items.query(JdbcProjectRepository::mapQueryRow).list(),
                count.query(Long.class).single());
    }

    @Override
    public Map<UUID, Long> countVisibleCurrentByWorkspace(CurrentActor actor,
                                                          Collection<UUID> workspaceIds) {
        if (workspaceIds.isEmpty()) return Map.of();
        return visible(jdbcClient.sql("SELECT p.workspace_id, count(*) AS visible_count "
                        + VISIBLE_FROM + " AND p.workspace_id IN (:workspaceIds) "
                        + "AND p.lifecycle IN ('DRAFT','ACTIVE') GROUP BY p.workspace_id"), actor)
                .param("workspaceIds", workspaceIds)
                .query((rs, row) -> Map.entry(rs.getObject("workspace_id", UUID.class),
                        rs.getLong("visible_count"))).list().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
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

    private static ProjectQueryRow mapQueryRow(ResultSet rs, int row) throws SQLException {
        return new ProjectQueryRow(map(rs, row), rs.getString("workspace_code"),
                rs.getString("workspace_name"), ProjectMembershipModels.ActorAccess.valueOf(
                rs.getString("actor_access")));
    }

    private static JdbcClient.StatementSpec visible(JdbcClient.StatementSpec statement,
                                                     CurrentActor actor) {
        return statement.param("actorUserId", actor.userId()).param("companyId", actor.companyId())
                .param("admin", actor.hasRole(PlatformRoleCode.COMPANY_ADMIN));
    }

    private static JdbcClient.StatementSpec filters(JdbcClient.StatementSpec statement,
            UUID workspaceId, ProjectType projectType, ProjectLifecycleFilter lifecycle,
            UUID productId) {
        boolean current = lifecycle == null;
        return statement.param("allWorkspaces", workspaceId == null)
                .param("workspaceId", workspaceId == null ? new UUID(0, 0) : workspaceId)
                .param("allTypes", projectType == null)
                .param("projectType", projectType == null ? "SOFTWARE_DEVELOPMENT" : projectType.name())
                .param("allProducts", productId == null)
                .param("productId", productId == null ? new UUID(0, 0) : productId)
                .param("draft", current || lifecycle == ProjectLifecycleFilter.DRAFT
                        || lifecycle == ProjectLifecycleFilter.ALL)
                .param("active", current || lifecycle == ProjectLifecycleFilter.ACTIVE
                        || lifecycle == ProjectLifecycleFilter.ALL)
                .param("archived", lifecycle == ProjectLifecycleFilter.ARCHIVED
                        || lifecycle == ProjectLifecycleFilter.ALL);
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
