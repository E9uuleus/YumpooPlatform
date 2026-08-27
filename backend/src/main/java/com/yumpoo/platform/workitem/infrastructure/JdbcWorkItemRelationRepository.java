package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.WorkItemRelationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcWorkItemRelationRepository implements WorkItemRelationRepository {
    private final JdbcClient jdbc;

    public JdbcWorkItemRelationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertParentChild(ParentChildRelation relation) {
        return jdbc.sql("""
                INSERT INTO yumpoo.work_item_relation (
                    id, company_id, relation_type, left_work_item_id, right_work_item_id,
                    left_project_id, right_project_id, created_by_user_id, created_at, row_version
                ) VALUES (
                    :id, :companyId, 'PARENT_CHILD', :parentWorkItemId, :childWorkItemId,
                    :projectId, :projectId, :createdByUserId, :createdAt, 0
                )
                """).param("id", relation.id()).param("companyId", relation.companyId())
                .param("parentWorkItemId", relation.parentWorkItemId())
                .param("childWorkItemId", relation.childWorkItemId())
                .param("projectId", relation.projectId())
                .param("createdByUserId", relation.createdByUserId())
                .param("createdAt", OffsetDateTime.ofInstant(relation.createdAt(), ZoneOffset.UTC))
                .update() == 1;
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
}
