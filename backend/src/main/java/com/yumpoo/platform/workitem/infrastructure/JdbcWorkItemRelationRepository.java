package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.WorkItemRelationRepository;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.workitem.domain.WorkItemRelation;
import com.yumpoo.platform.workitem.domain.WorkItemRelationType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcWorkItemRelationRepository implements WorkItemRelationRepository {
    private static final String RELATION_COLUMNS = """
            relation.id, relation.company_id, relation.relation_type,
            relation.left_work_item_id, relation.right_work_item_id,
            relation.left_project_id, relation.right_project_id,
            relation.created_by_user_id, relation.created_at,
            relation.deleted_by_user_id, relation.deleted_at, relation.delete_reason,
            relation.row_version
            """;

    private static final String PROJECTION_COLUMNS = RELATION_COLUMNS + """
            , left_item.content_id AS left_content_id, left_item.item_no AS left_item_no,
            left_item.type AS left_type, left_item.title AS left_title,
            left_item.status_code AS left_status_code, left_item.deleted_at AS left_deleted_at,
            right_item.content_id AS right_content_id, right_item.item_no AS right_item_no,
            right_item.type AS right_type, right_item.title AS right_title,
            right_item.status_code AS right_status_code, right_item.deleted_at AS right_deleted_at
            """;

    private final JdbcClient jdbc;

    public JdbcWorkItemRelationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertParentChild(ParentChildRelation relation) {
        return insert(WorkItemRelation.create(relation.id(), relation.companyId(),
                WorkItemRelationType.PARENT_CHILD, relation.parentWorkItemId(),
                relation.childWorkItemId(), relation.projectId(), relation.projectId(),
                relation.createdByUserId(), relation.createdAt()));
    }

    @Override
    public boolean insert(WorkItemRelation relation) {
        return jdbc.sql("""
                INSERT INTO yumpoo.work_item_relation (
                    id, company_id, relation_type, left_work_item_id, right_work_item_id,
                    left_project_id, right_project_id, created_by_user_id, created_at, row_version
                ) VALUES (
                    :id, :companyId, :relationType, :leftWorkItemId, :rightWorkItemId,
                    :leftProjectId, :rightProjectId, :createdByUserId, :createdAt, 0
                )
                """).param("id", relation.id()).param("companyId", relation.companyId())
                .param("relationType", relation.relationType().name())
                .param("leftWorkItemId", relation.leftWorkItemId())
                .param("rightWorkItemId", relation.rightWorkItemId())
                .param("leftProjectId", relation.leftProjectId())
                .param("rightProjectId", relation.rightProjectId())
                .param("createdByUserId", relation.createdByUserId())
                .param("createdAt", OffsetDateTime.ofInstant(relation.createdAt(), ZoneOffset.UTC))
                .update() == 1;
    }

    @Override
    public Optional<WorkItemRelation> findById(UUID companyId, UUID relationId) {
        return jdbc.sql("SELECT " + RELATION_COLUMNS + " FROM yumpoo.work_item_relation relation "
                        + "WHERE relation.company_id=:companyId AND relation.id=:relationId")
                .param("companyId", companyId).param("relationId", relationId)
                .query(JdbcWorkItemRelationRepository::mapRelation).optional();
    }

    @Override
    public Optional<WorkItemRelation> lock(UUID companyId, UUID relationId) {
        return jdbc.sql("SELECT " + RELATION_COLUMNS + " FROM yumpoo.work_item_relation relation "
                        + "WHERE relation.company_id=:companyId AND relation.id=:relationId FOR UPDATE")
                .param("companyId", companyId).param("relationId", relationId)
                .query(JdbcWorkItemRelationRepository::mapRelation).optional();
    }

    @Override
    public Optional<WorkItemRelation> findActivePair(UUID companyId,
            WorkItemRelationType relationType, UUID leftWorkItemId, UUID rightWorkItemId) {
        return jdbc.sql("SELECT " + RELATION_COLUMNS + " FROM yumpoo.work_item_relation relation "
                        + "WHERE relation.company_id=:companyId AND relation.relation_type=:relationType "
                        + "AND relation.left_work_item_id=:leftWorkItemId "
                        + "AND relation.right_work_item_id=:rightWorkItemId "
                        + "AND relation.deleted_at IS NULL")
                .param("companyId", companyId).param("relationType", relationType.name())
                .param("leftWorkItemId", leftWorkItemId).param("rightWorkItemId", rightWorkItemId)
                .query(JdbcWorkItemRelationRepository::mapRelation).optional();
    }

    @Override
    public Optional<WorkItemRelation> findActiveParent(UUID companyId, UUID childWorkItemId) {
        return jdbc.sql("SELECT " + RELATION_COLUMNS + " FROM yumpoo.work_item_relation relation "
                        + "WHERE relation.company_id=:companyId AND relation.relation_type='PARENT_CHILD' "
                        + "AND relation.right_work_item_id=:childWorkItemId AND relation.deleted_at IS NULL")
                .param("companyId", companyId).param("childWorkItemId", childWorkItemId)
                .query(JdbcWorkItemRelationRepository::mapRelation).optional();
    }

    @Override
    public Optional<Projection> findProjection(UUID companyId, UUID relationId) {
        return jdbc.sql("SELECT " + PROJECTION_COLUMNS + projectionJoins()
                        + " WHERE relation.company_id=:companyId AND relation.id=:relationId")
                .param("companyId", companyId).param("relationId", relationId)
                .query(JdbcWorkItemRelationRepository::mapProjection).optional();
    }

    @Override
    public Optional<WorkItemRelation> softDelete(WorkItemRelation relation, long expectedVersion) {
        return jdbc.sql("""
                UPDATE yumpoo.work_item_relation
                   SET deleted_by_user_id=:deletedByUserId, deleted_at=:deletedAt,
                       delete_reason=:deleteReason, row_version=row_version+1
                 WHERE company_id=:companyId AND id=:relationId
                   AND deleted_at IS NULL AND row_version=:expectedVersion
                RETURNING id, company_id, relation_type, left_work_item_id, right_work_item_id,
                          left_project_id, right_project_id, created_by_user_id, created_at,
                          deleted_by_user_id, deleted_at, delete_reason, row_version
                """).param("deletedByUserId", relation.deletedByUserId())
                .param("deletedAt", OffsetDateTime.ofInstant(relation.deletedAt(), ZoneOffset.UTC))
                .param("deleteReason", relation.deleteReason()).param("companyId", relation.companyId())
                .param("relationId", relation.id()).param("expectedVersion", expectedVersion)
                .query((rs, row) -> new WorkItemRelation(rs.getObject("id", UUID.class),
                        rs.getObject("company_id", UUID.class),
                        WorkItemRelationType.valueOf(rs.getString("relation_type")),
                        rs.getObject("left_work_item_id", UUID.class),
                        rs.getObject("right_work_item_id", UUID.class),
                        rs.getObject("left_project_id", UUID.class),
                        rs.getObject("right_project_id", UUID.class),
                        rs.getObject("created_by_user_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("deleted_by_user_id", UUID.class),
                        rs.getObject("deleted_at", OffsetDateTime.class).toInstant(),
                        rs.getString("delete_reason"), rs.getLong("row_version"))).optional();
    }

    @Override
    public Set<UUID> findCounterpartProjectIds(UUID companyId, UUID workItemId) {
        return Set.copyOf(jdbc.sql("""
                SELECT DISTINCT CASE WHEN left_work_item_id=:workItemId
                                     THEN right_project_id ELSE left_project_id END
                  FROM yumpoo.work_item_relation
                 WHERE company_id=:companyId AND deleted_at IS NULL
                   AND (left_work_item_id=:workItemId OR right_work_item_id=:workItemId)
                """).param("companyId", companyId).param("workItemId", workItemId)
                .query(UUID.class).list());
    }

    @Override
    public List<Projection> findActiveForWorkItem(UUID companyId, UUID workItemId,
            WorkItemRelationType relationType, Collection<UUID> visibleProjectIds,
            OffsetPageRequest page) {
        String typePredicate = relationType == null ? "" : " AND relation.relation_type=:relationType";
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT " + PROJECTION_COLUMNS + projectionJoins()
                        + " WHERE relation.company_id=:companyId AND relation.deleted_at IS NULL"
                        + " AND (relation.left_work_item_id=:workItemId OR relation.right_work_item_id=:workItemId)"
                        + " AND (CASE WHEN relation.left_work_item_id=:workItemId THEN relation.right_project_id"
                        + " ELSE relation.left_project_id END) IN (:visibleProjectIds)"
                        + typePredicate + " ORDER BY relation.created_at DESC, relation.id ASC"
                        + " LIMIT :limit OFFSET :offset")
                .param("companyId", companyId).param("workItemId", workItemId)
                .param("visibleProjectIds", visibleProjectIds)
                .param("limit", page.size()).param("offset", Math.multiplyExact(page.page(), page.size()));
        if (relationType != null) statement = statement.param("relationType", relationType.name());
        return statement.query(JdbcWorkItemRelationRepository::mapProjection).list();
    }

    @Override
    public long countActiveForWorkItem(UUID companyId, UUID workItemId,
            WorkItemRelationType relationType, Collection<UUID> visibleProjectIds) {
        String typePredicate = relationType == null ? "" : " AND relation_type=:relationType";
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT count(*) FROM yumpoo.work_item_relation "
                        + "WHERE company_id=:companyId AND deleted_at IS NULL "
                        + "AND (left_work_item_id=:workItemId OR right_work_item_id=:workItemId)"
                        + " AND (CASE WHEN left_work_item_id=:workItemId THEN right_project_id"
                        + " ELSE left_project_id END) IN (:visibleProjectIds)"
                        + typePredicate)
                .param("companyId", companyId).param("workItemId", workItemId)
                .param("visibleProjectIds", visibleProjectIds);
        if (relationType != null) statement = statement.param("relationType", relationType.name());
        return statement.query(Long.class).single();
    }

    @Override
    public boolean hasHiddenForWorkItem(UUID companyId, UUID workItemId,
            Collection<UUID> visibleProjectIds) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM yumpoo.work_item_relation
                     WHERE company_id=:companyId AND deleted_at IS NULL
                       AND (left_work_item_id=:workItemId OR right_work_item_id=:workItemId)
                       AND (CASE WHEN left_work_item_id=:workItemId THEN right_project_id
                                 ELSE left_project_id END) NOT IN (:visibleProjectIds))
                """).param("companyId", companyId).param("workItemId", workItemId)
                .param("visibleProjectIds", visibleProjectIds).query(Boolean.class).single();
    }

    @Override
    public List<CandidateFacts> findCandidates(UUID companyId, UUID projectId,
            UUID excludedWorkItemId, String query, WorkItemRelationType relationType,
            boolean currentIsLeft, OffsetPageRequest page) {
        return jdbc.sql("""
                SELECT candidate.id, candidate.project_id, candidate.content_id,
                       candidate.item_no, candidate.type AS type_code, candidate.title,
                       candidate.status_code, candidate.deleted_at,
                       EXISTS (SELECT 1 FROM yumpoo.work_item_relation existing
                                WHERE existing.company_id=:companyId
                                  AND existing.relation_type=:relationType
                                  AND existing.left_work_item_id=pair.left_id
                                  AND existing.right_work_item_id=pair.right_id
                                  AND existing.deleted_at IS NULL) AS already_related,
                       CASE WHEN :parentChild THEN EXISTS (
                           SELECT 1 FROM yumpoo.work_item_relation parent_of_left
                            WHERE parent_of_left.company_id=:companyId
                              AND parent_of_left.relation_type='PARENT_CHILD'
                              AND parent_of_left.right_work_item_id=pair.left_id
                              AND parent_of_left.deleted_at IS NULL) ELSE false END AS parent_is_child,
                       CASE WHEN :parentChild THEN EXISTS (
                           SELECT 1 FROM yumpoo.work_item_relation child_of_right
                            WHERE child_of_right.company_id=:companyId
                              AND child_of_right.relation_type='PARENT_CHILD'
                              AND child_of_right.left_work_item_id=pair.right_id
                              AND child_of_right.deleted_at IS NULL) ELSE false END AS child_has_children,
                       active_parent.id AS active_parent_relation_id,
                       active_parent.row_version AS active_parent_version,
                       parent_item.id AS parent_id, parent_item.project_id AS parent_project_id,
                       parent_item.content_id AS parent_content_id,
                       parent_item.item_no AS parent_item_no, parent_item.type AS parent_type,
                       parent_item.title AS parent_title,
                       parent_item.status_code AS parent_status_code,
                       parent_item.deleted_at AS parent_deleted_at
                  FROM yumpoo.work_item candidate
                 CROSS JOIN LATERAL (
                       SELECT CASE WHEN :related AND candidate.id::text < CAST(:currentWorkItemId AS text)
                                      THEN candidate.id
                                  WHEN :related OR :currentIsLeft THEN :currentWorkItemId
                                  ELSE candidate.id END AS left_id,
                              CASE WHEN :related AND candidate.id::text < CAST(:currentWorkItemId AS text)
                                      THEN :currentWorkItemId
                                  WHEN :related OR :currentIsLeft THEN candidate.id
                                  ELSE :currentWorkItemId END AS right_id
                 ) pair
                  LEFT JOIN yumpoo.work_item_relation active_parent
                    ON :parentChild AND active_parent.company_id=:companyId
                   AND active_parent.relation_type='PARENT_CHILD'
                   AND active_parent.right_work_item_id=pair.right_id
                   AND active_parent.deleted_at IS NULL
                  LEFT JOIN yumpoo.work_item parent_item
                    ON parent_item.company_id=active_parent.company_id
                   AND parent_item.id=active_parent.left_work_item_id
                 WHERE candidate.company_id=:companyId AND candidate.project_id=:projectId
                   AND candidate.id<>:excludedWorkItemId AND candidate.deleted_at IS NULL
                   AND (lower(candidate.title) LIKE :query ESCAPE '\\'
                        OR lower(candidate.item_no) LIKE :query ESCAPE '\\')
                 ORDER BY candidate.item_sequence DESC, candidate.id ASC
                 LIMIT :limit OFFSET :offset
                """).param("companyId", companyId).param("projectId", projectId)
                .param("excludedWorkItemId", excludedWorkItemId).param("query", like(query))
                .param("currentWorkItemId", excludedWorkItemId)
                .param("relationType", relationType.name())
                .param("related", relationType == WorkItemRelationType.RELATED)
                .param("parentChild", relationType == WorkItemRelationType.PARENT_CHILD)
                .param("currentIsLeft", currentIsLeft)
                .param("limit", page.size()).param("offset", Math.multiplyExact(page.page(), page.size()))
                .query(JdbcWorkItemRelationRepository::mapCandidateFacts).list();
    }

    @Override
    public long countCandidates(UUID companyId, UUID projectId, UUID excludedWorkItemId,
            String query) {
        return jdbc.sql("""
                SELECT count(*) FROM yumpoo.work_item
                 WHERE company_id=:companyId AND project_id=:projectId
                   AND id<>:excludedWorkItemId AND deleted_at IS NULL
                   AND (lower(title) LIKE :query ESCAPE '\\'
                        OR lower(item_no) LIKE :query ESCAPE '\\')
                """).param("companyId", companyId).param("projectId", projectId)
                .param("excludedWorkItemId", excludedWorkItemId).param("query", like(query))
                .query(Long.class).single();
    }

    @Override
    public boolean hasActiveParent(UUID companyId, UUID workItemId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM yumpoo.work_item_relation
                     WHERE company_id=:companyId AND relation_type='PARENT_CHILD'
                       AND right_work_item_id=:workItemId AND deleted_at IS NULL
                )
                """).param("companyId", companyId).param("workItemId", workItemId)
                .query(Boolean.class).single());
    }

    @Override
    public boolean hasActiveChildren(UUID companyId, UUID workItemId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM yumpoo.work_item_relation
                     WHERE company_id=:companyId AND relation_type='PARENT_CHILD'
                       AND left_work_item_id=:workItemId AND deleted_at IS NULL
                )
                """).param("companyId", companyId).param("workItemId", workItemId)
                .query(Boolean.class).single());
    }

    @Override
    public boolean isActiveChildOf(UUID companyId, UUID parentWorkItemId, UUID childWorkItemId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM yumpoo.work_item_relation
                     WHERE company_id=:companyId AND relation_type='PARENT_CHILD'
                       AND left_work_item_id=:parentWorkItemId
                       AND right_work_item_id=:childWorkItemId AND deleted_at IS NULL
                )
                """).param("companyId", companyId)
                .param("parentWorkItemId", parentWorkItemId)
                .param("childWorkItemId", childWorkItemId)
                .query(Boolean.class).single());
    }

    @Override
    public Map<UUID, Long> countActiveChildren(UUID companyId,
            Collection<UUID> parentWorkItemIds) {
        if (parentWorkItemIds.isEmpty()) return Map.of();
        Map<UUID, Long> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT relation.left_work_item_id AS parent_id, count(*) AS child_count
                  FROM yumpoo.work_item_relation relation
                  JOIN yumpoo.work_item child
                    ON child.id=relation.right_work_item_id
                   AND child.company_id=relation.company_id
                 WHERE relation.company_id=:companyId
                   AND relation.relation_type='PARENT_CHILD'
                   AND relation.deleted_at IS NULL AND child.deleted_at IS NULL
                   AND relation.left_work_item_id IN (:parentIds)
                 GROUP BY relation.left_work_item_id
                """).param("companyId", companyId).param("parentIds", parentWorkItemIds)
                .query((rs, row) -> Map.entry(rs.getObject("parent_id", UUID.class),
                        rs.getLong("child_count"))).list()
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    private static String projectionJoins() {
        return " FROM yumpoo.work_item_relation relation"
                + " JOIN yumpoo.work_item left_item ON left_item.id=relation.left_work_item_id"
                + " AND left_item.company_id=relation.company_id"
                + " JOIN yumpoo.work_item right_item ON right_item.id=relation.right_work_item_id"
                + " AND right_item.company_id=relation.company_id";
    }

    private static WorkItemRelation mapRelation(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        OffsetDateTime deletedAt = rs.getObject("deleted_at", OffsetDateTime.class);
        return new WorkItemRelation(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                WorkItemRelationType.valueOf(rs.getString("relation_type")),
                rs.getObject("left_work_item_id", UUID.class),
                rs.getObject("right_work_item_id", UUID.class),
                rs.getObject("left_project_id", UUID.class),
                rs.getObject("right_project_id", UUID.class),
                rs.getObject("created_by_user_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("deleted_by_user_id", UUID.class),
                deletedAt == null ? null : deletedAt.toInstant(), rs.getString("delete_reason"),
                rs.getLong("row_version"));
    }

    private static Projection mapProjection(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        WorkItemRelation relation = mapRelation(rs, row);
        Endpoint left = new Endpoint(relation.leftWorkItemId(), relation.leftProjectId(),
                rs.getObject("left_content_id", UUID.class), rs.getString("left_item_no"),
                rs.getString("left_type"), rs.getString("left_title"),
                rs.getString("left_status_code"), rs.getObject("left_deleted_at") != null);
        Endpoint right = new Endpoint(relation.rightWorkItemId(), relation.rightProjectId(),
                rs.getObject("right_content_id", UUID.class), rs.getString("right_item_no"),
                rs.getString("right_type"), rs.getString("right_title"),
                rs.getString("right_status_code"), rs.getObject("right_deleted_at") != null);
        return new Projection(relation, left, right);
    }

    private static Endpoint mapEndpoint(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Endpoint(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("content_id", UUID.class), rs.getString("item_no"),
                rs.getString("type_code"), rs.getString("title"), rs.getString("status_code"),
                rs.getObject("deleted_at") != null);
    }

    private static CandidateFacts mapCandidateFacts(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        Endpoint item = mapEndpoint(rs, row);
        UUID activeParentRelationId = rs.getObject("active_parent_relation_id", UUID.class);
        Endpoint parent = activeParentRelationId == null ? null : new Endpoint(
                rs.getObject("parent_id", UUID.class),
                rs.getObject("parent_project_id", UUID.class),
                rs.getObject("parent_content_id", UUID.class), rs.getString("parent_item_no"),
                rs.getString("parent_type"), rs.getString("parent_title"),
                rs.getString("parent_status_code"), rs.getObject("parent_deleted_at") != null);
        return new CandidateFacts(item, rs.getBoolean("already_related"),
                rs.getBoolean("parent_is_child"), rs.getBoolean("child_has_children"),
                activeParentRelationId, rs.getLong("active_parent_version"), parent);
    }

    private static String like(String value) {
        return "%" + value.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
