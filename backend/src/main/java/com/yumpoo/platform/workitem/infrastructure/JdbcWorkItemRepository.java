package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemLocator;
import com.yumpoo.platform.workitem.application.WorkItemQuery;
import com.yumpoo.platform.workitem.application.WorkItemRepository;
import com.yumpoo.platform.workitem.application.WorkItemSortRanks;
import com.yumpoo.platform.workitem.domain.ContentWorkItemType;
import com.yumpoo.platform.workitem.domain.ContentViewType;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemPriority;
import com.yumpoo.platform.workitem.domain.WorkItemStatusCategory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcWorkItemRepository implements WorkItemRepository {
    private static final String COLUMNS = """
            id, company_id, project_id, content_id, item_sequence, item_no, type, title,
            status_code, status_category, priority, assignee_user_id, reporter_user_id,
            description, notes, timeline_start_date, timeline_end_date, due_date, rank,
            project_sort_key, row_version, created_at, created_by_user_id, updated_at, updated_by_user_id,
            deleted_at, deleted_by_user_id, delete_reason
            """;

    private final JdbcClient jdbc;

    public JdbcWorkItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long nextSequence(UUID companyId, UUID projectId) {
        return jdbc.sql("""
                INSERT INTO yumpoo.work_item_project_counter (project_id, company_id, last_sequence)
                VALUES (:projectId, :companyId, 1)
                ON CONFLICT (project_id) DO UPDATE
                   SET last_sequence = yumpoo.work_item_project_counter.last_sequence + 1
                 WHERE yumpoo.work_item_project_counter.company_id = EXCLUDED.company_id
                RETURNING last_sequence
                """).param("projectId", projectId).param("companyId", companyId)
                .query(Long.class).single();
    }

    @Override
    public boolean insert(WorkItem item) {
        JdbcClient.StatementSpec statement = jdbc.sql("""
                INSERT INTO yumpoo.work_item (
                    id, company_id, project_id, content_id, item_sequence, item_no, type,
                    title, status_code, status_category, priority, assignee_user_id,
                    reporter_user_id, description, notes, timeline_start_date,
                    timeline_end_date, due_date, rank, project_sort_key, row_version, created_at,
                    created_by_user_id, updated_at, updated_by_user_id, deleted_at,
                    deleted_by_user_id, delete_reason
                ) VALUES (
                    :id, :companyId, :projectId, :contentId, :itemSequence, :itemNo, :type,
                    :title, :statusCode, :statusCategory, :priority, :assigneeUserId,
                    :reporterUserId, :description, :notes, :timelineStartDate,
                    :timelineEndDate, :dueDate, :rank, :projectSortKey, :rowVersion, :createdAt,
                    :createdByUserId, :updatedAt, :updatedByUserId, :deletedAt,
                    :deletedByUserId, :deleteReason
                )
                """).param("id", item.id()).param("companyId", item.companyId())
                .param("projectId", item.projectId()).param("contentId", item.contentId())
                .param("itemSequence", item.itemSequence()).param("itemNo", item.itemNo())
                .param("type", item.type().name()).param("title", item.title())
                .param("statusCode", item.statusCode())
                .param("statusCategory", item.statusCategory().name())
                .param("reporterUserId", item.reporterUserId())
                .param("rowVersion", item.rowVersion())
                .param("createdAt", OffsetDateTime.ofInstant(item.createdAt(), ZoneOffset.UTC))
                .param("createdByUserId", item.createdByUserId())
                .param("updatedAt", OffsetDateTime.ofInstant(item.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", item.updatedByUserId());
        statement = nullable(statement, "priority", priorityName(item), Types.VARCHAR);
        statement = nullable(statement, "assigneeUserId", item.assigneeUserId(), Types.OTHER);
        statement = nullable(statement, "description", item.description(), Types.VARCHAR);
        statement = nullable(statement, "notes", item.notes(), Types.VARCHAR);
        statement = nullable(statement, "timelineStartDate", item.timelineStartDate(), Types.DATE);
        statement = nullable(statement, "timelineEndDate", item.timelineEndDate(), Types.DATE);
        statement = nullable(statement, "dueDate", item.dueDate(), Types.DATE);
        statement = nullable(statement, "rank", item.rank(), Types.VARCHAR);
        statement = statement.param("projectSortKey", item.projectSortKey());
        statement = nullable(statement, "deletedAt", item.deletedAt() == null ? null
                : OffsetDateTime.ofInstant(item.deletedAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        statement = nullable(statement, "deletedByUserId", item.deletedByUserId(), Types.OTHER);
        statement = nullable(statement, "deleteReason", item.deleteReason(), Types.VARCHAR);
        return statement.update() == 1;
    }

    @Override
    public Optional<WorkItemLocator> findLocator(UUID companyId, UUID workItemId) {
        return findLocator(companyId, workItemId, false);
    }

    @Override
    public Optional<WorkItemLocator> findLocatorIncludingDeleted(UUID companyId, UUID workItemId) {
        return findLocator(companyId, workItemId, true);
    }

    private Optional<WorkItemLocator> findLocator(UUID companyId, UUID workItemId,
            boolean includeDeleted) {
        return jdbc.sql("SELECT id, project_id, content_id FROM yumpoo.work_item "
                        + "WHERE company_id=:companyId AND id=:workItemId"
                        + (includeDeleted ? "" : " AND deleted_at IS NULL"))
                .param("companyId", companyId).param("workItemId", workItemId)
                .query((rs, row) -> new WorkItemLocator(rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("content_id", UUID.class))).optional();
    }

    @Override
    public Optional<WorkItem> find(UUID companyId, UUID projectId, UUID contentId, UUID workItemId) {
        return find(companyId, projectId, contentId, workItemId, false, false);
    }

    @Override
    public Optional<WorkItem> findIncludingDeleted(UUID companyId, UUID projectId,
            UUID contentId, UUID workItemId) {
        return find(companyId, projectId, contentId, workItemId, false, true);
    }

    @Override
    public Optional<WorkItem> lock(UUID companyId, UUID projectId, UUID contentId, UUID workItemId) {
        return find(companyId, projectId, contentId, workItemId, true, false);
    }

    @Override
    public Optional<WorkItem> lockIncludingDeleted(UUID companyId, UUID projectId,
            UUID contentId, UUID workItemId) {
        return find(companyId, projectId, contentId, workItemId, true, true);
    }

    @Override
    public Optional<WorkItem> update(WorkItem item, long expectedVersion) {
        JdbcClient.StatementSpec statement = jdbc.sql("""
                UPDATE yumpoo.work_item SET title=:title, priority=:priority,
                    assignee_user_id=:assigneeUserId, description=:description, notes=:notes,
                    timeline_start_date=:timelineStartDate, timeline_end_date=:timelineEndDate,
                    due_date=:dueDate, row_version=row_version+1, updated_at=:updatedAt,
                    updated_by_user_id=:updatedByUserId
                WHERE company_id=:companyId AND project_id=:projectId AND content_id=:contentId
                  AND id=:id AND deleted_at IS NULL AND row_version=:expectedVersion
                RETURNING %s
                """.formatted(COLUMNS)).param("title", item.title())
                .param("updatedAt", OffsetDateTime.ofInstant(item.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", item.updatedByUserId())
                .param("companyId", item.companyId()).param("projectId", item.projectId())
                .param("contentId", item.contentId()).param("id", item.id())
                .param("expectedVersion", expectedVersion);
        statement = nullable(statement, "priority", priorityName(item), Types.VARCHAR);
        statement = nullable(statement, "assigneeUserId", item.assigneeUserId(), Types.OTHER);
        statement = nullable(statement, "description", item.description(), Types.VARCHAR);
        statement = nullable(statement, "notes", item.notes(), Types.VARCHAR);
        statement = nullable(statement, "timelineStartDate", item.timelineStartDate(), Types.DATE);
        statement = nullable(statement, "timelineEndDate", item.timelineEndDate(), Types.DATE);
        statement = nullable(statement, "dueDate", item.dueDate(), Types.DATE);
        return statement.query(JdbcWorkItemRepository::map).optional();
    }

    @Override
    public Optional<WorkItem> transition(WorkItem item, long expectedVersion) {
        return jdbc.sql("""
                UPDATE yumpoo.work_item
                   SET status_code=:statusCode, status_category=:statusCategory, rank=:rank,
                       row_version=row_version+1, updated_at=:updatedAt,
                       updated_by_user_id=:updatedByUserId
                 WHERE company_id=:companyId AND project_id=:projectId AND content_id=:contentId
                   AND id=:id AND deleted_at IS NULL AND row_version=:expectedVersion
                RETURNING %s
                """.formatted(COLUMNS))
                .param("statusCode", item.statusCode())
                .param("statusCategory", item.statusCategory().name())
                .param("rank", item.rank())
                .param("updatedAt", OffsetDateTime.ofInstant(item.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", item.updatedByUserId())
                .param("companyId", item.companyId()).param("projectId", item.projectId())
                .param("contentId", item.contentId()).param("id", item.id())
                .param("expectedVersion", expectedVersion)
                .query(JdbcWorkItemRepository::map).optional();
    }

    @Override
    public Optional<WorkItem> softDelete(WorkItem item, long expectedVersion) {
        return jdbc.sql("""
                UPDATE yumpoo.work_item
                   SET deleted_at=:deletedAt, deleted_by_user_id=:deletedByUserId,
                       delete_reason=:deleteReason, row_version=row_version+1,
                       updated_at=:updatedAt, updated_by_user_id=:updatedByUserId
                 WHERE company_id=:companyId AND project_id=:projectId AND content_id=:contentId
                   AND id=:id AND deleted_at IS NULL AND row_version=:expectedVersion
                RETURNING %s
                """.formatted(COLUMNS))
                .param("deletedAt", OffsetDateTime.ofInstant(item.deletedAt(), ZoneOffset.UTC))
                .param("deletedByUserId", item.deletedByUserId())
                .param("deleteReason", item.deleteReason())
                .param("updatedAt", OffsetDateTime.ofInstant(item.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", item.updatedByUserId())
                .param("companyId", item.companyId()).param("projectId", item.projectId())
                .param("contentId", item.contentId()).param("id", item.id())
                .param("expectedVersion", expectedVersion)
                .query(JdbcWorkItemRepository::map).optional();
    }

    @Override
    public Optional<WorkItem> restore(WorkItem item, long expectedVersion) {
        return jdbc.sql("""
                UPDATE yumpoo.work_item
                   SET rank=:rank, project_sort_key=:projectSortKey,
                       deleted_at=NULL, deleted_by_user_id=NULL, delete_reason=NULL,
                       row_version=row_version+1, updated_at=:updatedAt,
                       updated_by_user_id=:updatedByUserId
                 WHERE company_id=:companyId AND project_id=:projectId AND content_id=:contentId
                   AND id=:id AND deleted_at IS NOT NULL AND row_version=:expectedVersion
                RETURNING %s
                """.formatted(COLUMNS))
                .param("rank", item.rank())
                .param("projectSortKey", item.projectSortKey())
                .param("updatedAt", OffsetDateTime.ofInstant(item.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", item.updatedByUserId())
                .param("companyId", item.companyId()).param("projectId", item.projectId())
                .param("contentId", item.contentId()).param("id", item.id())
                .param("expectedVersion", expectedVersion)
                .query(JdbcWorkItemRepository::map).optional();
    }

    @Override
    public void lockRankLanes(UUID contentId, Collection<String> statuses) {
        List<String> ordered = statuses.stream().distinct().sorted().toList();
        for (String status : ordered) {
            jdbc.sql("INSERT INTO yumpoo.work_item_rank_lane (content_id, status_code) "
                            + "VALUES (:contentId, :statusCode) ON CONFLICT DO NOTHING")
                    .param("contentId", contentId).param("statusCode", status).update();
        }
        jdbc.sql("SELECT status_code FROM yumpoo.work_item_rank_lane "
                        + "WHERE content_id=:contentId AND status_code IN (:statuses) "
                        + "ORDER BY status_code FOR UPDATE")
                .param("contentId", contentId).param("statuses", ordered)
                .query(String.class).list();
    }

    @Override
    public List<RankedWorkItem> findRankOrder(UUID companyId, UUID projectId, UUID contentId,
            String statusCode) {
        return jdbc.sql("SELECT id, rank FROM yumpoo.work_item WHERE company_id=:companyId "
                        + "AND project_id=:projectId AND content_id=:contentId "
                        + "AND status_code=:statusCode AND deleted_at IS NULL ORDER BY rank ASC, id ASC")
                .param("companyId", companyId).param("projectId", projectId)
                .param("contentId", contentId).param("statusCode", statusCode)
                .query((rs, row) -> new RankedWorkItem(rs.getObject("id", UUID.class),
                        rs.getString("rank"))).list();
    }

    @Override
    public void rewriteRanks(UUID companyId, UUID projectId, UUID contentId, String statusCode,
            Map<UUID, String> ranks) {
        if (ranks.isEmpty()) return;
        StringBuilder cases = new StringBuilder("CASE id");
        Map<String, Object> parameters = baseParameters(companyId, projectId, contentId);
        List<UUID> ids = new ArrayList<>();
        int index = 0;
        for (Map.Entry<UUID, String> entry : ranks.entrySet()) {
            String id = "rankId" + index;
            String rank = "rankValue" + index;
            cases.append(" WHEN :").append(id).append(" THEN :").append(rank);
            parameters.put(id, entry.getKey());
            parameters.put(rank, entry.getValue());
            ids.add(entry.getKey());
            index++;
        }
        parameters.put("statusCode", statusCode);
        parameters.put("rankIds", ids);
        JdbcClient.StatementSpec statement = jdbc.sql("UPDATE yumpoo.work_item SET rank="
                + cases.append(" ELSE rank END").toString()
                + " WHERE company_id=:companyId AND project_id=:projectId AND content_id=:contentId"
                + " AND status_code=:statusCode AND deleted_at IS NULL AND id IN (:rankIds)");
        int updated = bind(statement, parameters).update();
        if (updated != ranks.size()) throw new IllegalStateException("Kanban rank rebalance lost rows");
    }

    @Override
    public void lockProjectOrder(UUID companyId, UUID projectId) {
        jdbc.sql("INSERT INTO yumpoo.work_item_project_order (project_id, company_id) "
                        + "VALUES (:projectId, :companyId) ON CONFLICT DO NOTHING")
                .param("projectId", projectId).param("companyId", companyId).update();
        jdbc.sql("SELECT project_id FROM yumpoo.work_item_project_order "
                        + "WHERE project_id=:projectId AND company_id=:companyId FOR UPDATE")
                .param("projectId", projectId).param("companyId", companyId)
                .query(UUID.class).single();
    }

    @Override
    public Optional<WorkItem> lockProjectItem(UUID companyId, UUID projectId, UUID workItemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM yumpoo.work_item "
                        + "WHERE company_id=:companyId AND project_id=:projectId "
                        + "AND id=:workItemId AND deleted_at IS NULL FOR UPDATE")
                .param("companyId", companyId).param("projectId", projectId)
                .param("workItemId", workItemId).query(JdbcWorkItemRepository::map).optional();
    }

    @Override
    public Optional<RankedProjectWorkItem> findProjectNeighborBefore(UUID companyId,
            UUID projectId, String projectSortKey, UUID excludedId) {
        return projectNeighbor(companyId, projectId, projectSortKey, excludedId, false);
    }

    @Override
    public Optional<RankedProjectWorkItem> findProjectNeighborAfter(UUID companyId,
            UUID projectId, String projectSortKey, UUID excludedId) {
        return projectNeighbor(companyId, projectId, projectSortKey, excludedId, true);
    }

    @Override
    public Optional<RankedProjectWorkItem> findFirstProjectRank(UUID companyId, UUID projectId,
            UUID excludedId) {
        return jdbc.sql("SELECT id, project_sort_key FROM yumpoo.work_item "
                        + "WHERE company_id=:companyId AND project_id=:projectId "
                        + "AND deleted_at IS NULL AND id<>:excludedId "
                        + "ORDER BY project_sort_key ASC, id ASC LIMIT 1")
                .param("companyId", companyId).param("projectId", projectId)
                .param("excludedId", excludedId)
                .query((rs, row) -> new RankedProjectWorkItem(
                        rs.getObject("id", UUID.class), rs.getString("project_sort_key")))
                .optional();
    }

    @Override
    public boolean projectSortKeyOccupied(UUID companyId, UUID projectId, String projectSortKey,
            UUID excludedId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM yumpoo.work_item "
                        + "WHERE company_id=:companyId AND project_id=:projectId AND deleted_at IS NULL "
                        + "AND id<>:excludedId AND project_sort_key=:projectSortKey)")
                .param("companyId", companyId).param("projectId", projectId)
                .param("excludedId", excludedId).param("projectSortKey", projectSortKey)
                .query(Boolean.class).single());
    }

    private Optional<RankedProjectWorkItem> projectNeighbor(UUID companyId, UUID projectId,
            String key, UUID excludedId, boolean after) {
        String comparison = after ? ">" : "<";
        String direction = after ? "ASC" : "DESC";
        return jdbc.sql("SELECT id, project_sort_key FROM yumpoo.work_item "
                        + "WHERE company_id=:companyId AND project_id=:projectId "
                        + "AND deleted_at IS NULL AND id<>:excludedId AND project_sort_key "
                        + comparison + " :projectSortKey ORDER BY project_sort_key " + direction
                        + ", id " + direction + " LIMIT 1")
                .param("companyId", companyId).param("projectId", projectId)
                .param("excludedId", excludedId).param("projectSortKey", key)
                .query((rs, row) -> new RankedProjectWorkItem(
                        rs.getObject("id", UUID.class), rs.getString("project_sort_key")))
                .optional();
    }

    @Override
    public List<RankedProjectWorkItem> findProjectRankWindow(UUID companyId, UUID projectId,
            String pivotKey, UUID excludedId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("project rank window limit");
        return jdbc.sql("SELECT id, project_sort_key FROM (SELECT id, project_sort_key "
                        + "FROM yumpoo.work_item WHERE company_id=:companyId AND project_id=:projectId "
                        + "AND deleted_at IS NULL AND id<>:excludedId ORDER BY "
                        + "abs(project_sort_key::numeric - :pivotKey::numeric), project_sort_key ASC LIMIT :limit) w "
                        + "ORDER BY project_sort_key ASC, id ASC")
                .param("companyId", companyId).param("projectId", projectId)
                .param("excludedId", excludedId).param("pivotKey", pivotKey).param("limit", limit)
                .query((rs, row) -> new RankedProjectWorkItem(
                        rs.getObject("id", UUID.class), rs.getString("project_sort_key"))).list();
    }

    @Override
    public void rewriteProjectSortKeys(UUID companyId, UUID projectId, Map<UUID, String> ranks) {
        if (ranks.isEmpty()) return;
        if (ranks.size() > 100) throw new IllegalArgumentException("project rank rebalance limit");
        StringBuilder cases = new StringBuilder("CASE id");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("companyId", companyId); parameters.put("projectId", projectId);
        List<UUID> ids = new ArrayList<>();
        int index = 0;
        for (Map.Entry<UUID, String> entry : ranks.entrySet()) {
            String id = "projectRankId" + index;
            String rank = "projectRankValue" + index;
            cases.append(" WHEN :").append(id).append(" THEN :").append(rank);
            parameters.put(id, entry.getKey()); parameters.put(rank, entry.getValue());
            ids.add(entry.getKey()); index++;
        }
        parameters.put("projectRankIds", ids);
        JdbcClient.StatementSpec statement = jdbc.sql("UPDATE yumpoo.work_item SET project_sort_key="
                + cases.append(" ELSE project_sort_key END")
                + " WHERE company_id=:companyId AND project_id=:projectId AND deleted_at IS NULL "
                + "AND id IN (:projectRankIds)");
        int updated = bind(statement, parameters).update();
        if (updated != ranks.size()) throw new IllegalStateException("project order rebalance lost rows");
    }

    @Override
    public Optional<WorkItem> reorderProject(WorkItem item, long expectedVersion) {
        return jdbc.sql("UPDATE yumpoo.work_item SET project_sort_key=:projectSortKey, "
                        + "row_version=row_version+1, updated_by_user_id=:updatedByUserId "
                        + "WHERE company_id=:companyId AND project_id=:projectId AND id=:id "
                        + "AND deleted_at IS NULL AND row_version=:expectedVersion RETURNING " + COLUMNS)
                .param("projectSortKey", item.projectSortKey())
                .param("updatedByUserId", item.updatedByUserId())
                .param("companyId", item.companyId()).param("projectId", item.projectId())
                .param("id", item.id()).param("expectedVersion", expectedVersion)
                .query(JdbcWorkItemRepository::map).optional();
    }

    private Optional<WorkItem> find(UUID companyId, UUID projectId, UUID contentId,
            UUID workItemId, boolean lock, boolean includeDeleted) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM yumpoo.work_item WHERE company_id=:companyId "
                        + "AND project_id=:projectId AND content_id=:contentId AND id=:workItemId "
                        + (includeDeleted ? "" : "AND deleted_at IS NULL ")
                        + (lock ? "FOR UPDATE" : ""))
                .param("companyId", companyId).param("projectId", projectId)
                .param("contentId", contentId).param("workItemId", workItemId)
                .query(JdbcWorkItemRepository::map).optional();
    }

    @Override
    public Set<UUID> findParticipantUserIds(UUID companyId, UUID projectId, UUID contentId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT reporter_user_id AS user_id
                  FROM yumpoo.work_item
                 WHERE company_id=:companyId AND project_id=:projectId
                   AND content_id=:contentId AND deleted_at IS NULL
                UNION
                SELECT assignee_user_id AS user_id
                  FROM yumpoo.work_item
                 WHERE company_id=:companyId AND project_id=:projectId
                   AND content_id=:contentId AND deleted_at IS NULL
                   AND assignee_user_id IS NOT NULL
                """).param("companyId", companyId).param("projectId", projectId)
                .param("contentId", contentId).query(UUID.class).list());
    }

    @Override
    public Set<UUID> findProjectParticipantUserIds(UUID companyId, UUID projectId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT reporter_user_id AS user_id
                  FROM yumpoo.work_item
                 WHERE company_id=:companyId AND project_id=:projectId AND deleted_at IS NULL
                UNION
                SELECT assignee_user_id AS user_id
                  FROM yumpoo.work_item
                 WHERE company_id=:companyId AND project_id=:projectId AND deleted_at IS NULL
                   AND assignee_user_id IS NOT NULL
                """).param("companyId", companyId).param("projectId", projectId)
                .query(UUID.class).list());
    }

    @Override
    public List<WorkItem> findPage(UUID companyId, UUID projectId, UUID contentId,
            WorkItemQuery query, WorkItemSortRanks ranks, ContentViewType view,
            OffsetPageRequest page) {
        Map<String, Object> parameters = baseParameters(companyId, projectId, contentId);
        String where = where(query, parameters);
        String orderBy = view == ContentViewType.KANBAN ? "rank ASC, id ASC"
                : query.sorts().isEmpty() ? "item_sequence DESC, id ASC"
                : orderBy(query, ranks, parameters);
        parameters.put("limit", page.size());
        parameters.put("offset", Math.multiplyExact(page.page(), page.size()));
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT " + COLUMNS
                + " FROM yumpoo.work_item" + where + " ORDER BY " + orderBy
                + " LIMIT :limit OFFSET :offset");
        return bind(statement, parameters).query(JdbcWorkItemRepository::map).list();
    }

    @Override
    public long countPage(UUID companyId, UUID projectId, UUID contentId, WorkItemQuery query) {
        Map<String, Object> parameters = baseParameters(companyId, projectId, contentId);
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT count(*) FROM yumpoo.work_item"
                + where(query, parameters));
        return bind(statement, parameters).query(Long.class).single();
    }

    @Override
    public List<WorkItem> findProjectPage(UUID companyId, UUID projectId,
            WorkItemQuery query, WorkItemSortRanks ranks, ContentViewType view,
            OffsetPageRequest page) {
        Map<String, Object> parameters = baseProjectParameters(companyId, projectId);
        String where = where(query, parameters, false);
        String orderBy = view == ContentViewType.KANBAN
                ? "updated_at DESC, id ASC" : orderBy(query, ranks, parameters);
        parameters.put("limit", page.size());
        parameters.put("offset", Math.multiplyExact(page.page(), page.size()));
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT " + COLUMNS
                + " FROM yumpoo.work_item" + where + " ORDER BY " + orderBy
                + " LIMIT :limit OFFSET :offset");
        return bind(statement, parameters).query(JdbcWorkItemRepository::map).list();
    }

    @Override
    public List<WorkItem> findProjectCursorPage(UUID companyId, UUID projectId,
            WorkItemQuery query, WorkItemSortRanks ranks, ContentViewType view,
            ProjectCursorAnchor anchor, int limit) {
        Map<String, Object> parameters = baseProjectParameters(companyId, projectId);
        StringBuilder where = new StringBuilder(where(query, parameters, false));
        String orderBy;
        if (view == ContentViewType.KANBAN) {
            orderBy = "updated_at DESC, id ASC";
            if (anchor != null) {
                where.append(" AND (updated_at < :cursorUpdatedAt OR (updated_at = :cursorUpdatedAt")
                        .append(" AND id > :cursorId))");
                parameters.put("cursorUpdatedAt",
                        OffsetDateTime.ofInstant(anchor.updatedAt(), ZoneOffset.UTC));
                parameters.put("cursorId", anchor.id());
            }
        } else if (query.sorts().isEmpty()) {
            orderBy = "project_sort_key ASC, id ASC";
            if (anchor != null) {
                where.append(" AND (project_sort_key > :cursorProjectSortKey ")
                        .append("OR (project_sort_key = :cursorProjectSortKey AND id > :cursorId))");
                parameters.put("cursorProjectSortKey", anchor.projectSortKey());
                parameters.put("cursorId", anchor.id());
            }
        } else {
            List<SeekTerm> terms = projectSortTerms(query, ranks, anchor, parameters);
            orderBy = terms.stream().map(term -> term.expression() + " " + term.direction())
                    .collect(java.util.stream.Collectors.joining(", "));
            if (anchor != null) where.append(" AND (").append(seekPredicate(terms, parameters))
                    .append(')');
        }
        parameters.put("limit", limit);
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT " + COLUMNS
                + " FROM yumpoo.work_item" + where + " ORDER BY " + orderBy + " LIMIT :limit");
        return bind(statement, parameters).query(JdbcWorkItemRepository::map).list();
    }

    @Override
    public List<FilterOptionCount> findProjectFilterOptions(UUID companyId, UUID projectId,
            WorkItemQuery query, String field, String afterValue, int limit) {
        String expression = switch (field) {
            case "TITLE" -> "title";
            case "ASSIGNEE" -> "coalesce(assignee_user_id::text, '__NULL__')";
            case "STATUS" -> "status_code";
            case "PRIORITY" -> "coalesce(priority, '__NULL__')";
            case "CONTENT" -> "content_id::text";
            case "DUE_DATE" -> "coalesce(due_date::text, '__NULL__')";
            case "UPDATED_AT" -> "updated_at::date::text";
            default -> throw new IllegalArgumentException("unsupported project filter option field");
        };
        Map<String, Object> parameters = baseProjectParameters(companyId, projectId);
        StringBuilder predicate = new StringBuilder(where(query, parameters, false));
        if (afterValue != null) {
            predicate.append(" AND ").append(expression).append(" > :afterValue");
            parameters.put("afterValue", afterValue);
        }
        parameters.put("limit", limit);
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT " + expression
                + " AS option_value, count(*) AS option_count FROM yumpoo.work_item "
                + predicate + " GROUP BY " + expression + " ORDER BY " + expression
                + " ASC LIMIT :limit");
        return bind(statement, parameters).query((rs, row) -> new FilterOptionCount(
                rs.getString("option_value"), rs.getLong("option_count"))).list();
    }

    @Override
    public long countProjectPage(UUID companyId, UUID projectId, WorkItemQuery query) {
        Map<String, Object> parameters = baseProjectParameters(companyId, projectId);
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT count(*) FROM yumpoo.work_item"
                + where(query, parameters, false));
        return bind(statement, parameters).query(Long.class).single();
    }

    @Override
    public long countOpenByProject(UUID companyId, UUID projectId) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.work_item WHERE company_id=:companyId "
                        + "AND project_id=:projectId AND deleted_at IS NULL "
                        + "AND status_category IN ('TODO','IN_PROGRESS')")
                .param("companyId", companyId).param("projectId", projectId)
                .query(Long.class).single();
    }

    @Override
    public long countOpenByContent(UUID companyId, UUID projectId, UUID contentId) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.work_item WHERE company_id=:companyId "
                        + "AND project_id=:projectId AND content_id=:contentId AND deleted_at IS NULL "
                        + "AND status_category IN ('TODO','IN_PROGRESS')")
                .param("companyId", companyId).param("projectId", projectId)
                .param("contentId", contentId).query(Long.class).single();
    }

    private static Map<String, Object> baseParameters(UUID companyId, UUID projectId,
            UUID contentId) {
        Map<String, Object> parameters = baseProjectParameters(companyId, projectId);
        parameters.put("contentId", contentId);
        return parameters;
    }

    private static Map<String, Object> baseProjectParameters(UUID companyId, UUID projectId) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("companyId", companyId);
        parameters.put("projectId", projectId);
        return parameters;
    }

    private static String where(WorkItemQuery query, Map<String, Object> parameters) {
        return where(query, parameters, true);
    }

    private static String where(WorkItemQuery query, Map<String, Object> parameters,
            boolean contentScoped) {
        StringBuilder sql = new StringBuilder(" WHERE company_id=:companyId")
                .append(" AND project_id=:projectId")
                .append(contentScoped ? " AND content_id=:contentId" : "")
                .append(" AND deleted_at IS NULL");
        if (query.query() != null) {
            sql.append(" AND (lower(title) LIKE :titleQuery ESCAPE '\\' "
                    + "OR lower(item_no) LIKE :titleQuery ESCAPE '\\')");
            parameters.put("titleQuery", "%" + escapeLike(query.query().toLowerCase(Locale.ROOT)) + "%");
        }
        if (!query.statuses().isEmpty()) {
            sql.append(" AND status_code IN (:statuses)");
            parameters.put("statuses", query.statuses());
        }
        if (!query.priorities().isEmpty()) {
            sql.append(" AND priority IN (:priorities)");
            parameters.put("priorities", query.priorities().stream().map(Enum::name).toList());
        }
        if (!query.assigneeUserIds().isEmpty()) {
            sql.append(" AND assignee_user_id IN (:assigneeUserIds)");
            parameters.put("assigneeUserIds", query.assigneeUserIds());
        }
        if (!query.contentIds().isEmpty()) {
            sql.append(" AND content_id IN (:contentIds)");
            parameters.put("contentIds", query.contentIds());
        }
        if (query.dueFrom() != null) {
            sql.append(" AND due_date >= :dueFrom");
            parameters.put("dueFrom", query.dueFrom());
        }
        if (query.dueTo() != null) {
            sql.append(" AND due_date <= :dueTo");
            parameters.put("dueTo", query.dueTo());
        }
        if (query.updatedAfter() != null) {
            sql.append(" AND updated_at > :updatedAfter");
            parameters.put("updatedAfter",
                    OffsetDateTime.ofInstant(query.updatedAfter(), ZoneOffset.UTC));
        }
        return sql.toString();
    }

    private static String orderBy(WorkItemQuery query, WorkItemSortRanks ranks,
            Map<String, Object> parameters) {
        List<String> order = new ArrayList<>();
        int index = 0;
        for (WorkItemQuery.Sort sort : query.sorts()) {
            String direction = sort.direction().name();
            switch (sort.field()) {
                case ITEM_NO -> order.add("item_sequence " + direction);
                case TITLE -> order.add("lower(title) " + direction);
                case STATUS -> addRankedTextOrder(order, parameters, "status_code",
                        "status", ranks.statuses(), direction, index);
                case PRIORITY -> {
                    order.add("CASE WHEN priority IS NULL THEN 1 ELSE 0 END ASC");
                    order.add("CASE priority WHEN 'LOW' THEN 0 WHEN 'MEDIUM' THEN 1 "
                            + "WHEN 'HIGH' THEN 2 WHEN 'URGENT' THEN 3 ELSE 4 END " + direction);
                }
                case ASSIGNEE -> addRankedUserOrder(order, parameters, "assignee_user_id",
                        "assignee", ranks.assignees(), direction, index);
                case REPORTER -> addRankedUserOrder(order, parameters, "reporter_user_id",
                        "reporter", ranks.reporters(), direction, index);
                case TIMELINE_START_DATE -> order.add("timeline_start_date " + direction + " NULLS LAST");
                case TIMELINE_END_DATE -> order.add("timeline_end_date " + direction + " NULLS LAST");
                case DUE_DATE -> order.add("due_date " + direction + " NULLS LAST");
                case UPDATED_AT -> order.add("updated_at " + direction + " NULLS LAST");
            }
            index++;
        }
        order.add("id ASC");
        return String.join(", ", order);
    }

    private record SeekTerm(String expression, String direction, Object anchorValue) {}

    private static List<SeekTerm> projectSortTerms(WorkItemQuery query,
            WorkItemSortRanks ranks, ProjectCursorAnchor anchor, Map<String, Object> parameters) {
        List<SeekTerm> terms = new ArrayList<>();
        int index = 0;
        for (WorkItemQuery.Sort sort : query.sorts()) {
            String direction = sort.direction().name();
            switch (sort.field()) {
                case ITEM_NO -> terms.add(new SeekTerm("item_sequence", direction,
                        anchor == null ? 0L : anchor.itemSequence()));
                case TITLE -> terms.add(new SeekTerm("lower(title)", direction,
                        anchor == null ? "" : anchor.title().toLowerCase(Locale.ROOT)));
                case STATUS -> {
                    String expression = rankedTextExpression(parameters, "status_code",
                            "projectStatus", ranks.statuses(), index);
                    terms.add(new SeekTerm(expression, direction, anchor == null ? 0
                            : ranks.statuses().getOrDefault(anchor.statusCode(), ranks.statuses().size())));
                    terms.add(new SeekTerm("status_code", "ASC",
                            anchor == null ? "" : anchor.statusCode()));
                }
                case PRIORITY -> {
                    terms.add(new SeekTerm("CASE WHEN priority IS NULL THEN 1 ELSE 0 END", "ASC",
                            anchor != null && anchor.priority() == null ? 1 : 0));
                    terms.add(new SeekTerm("CASE priority WHEN 'LOW' THEN 0 WHEN 'MEDIUM' THEN 1 "
                            + "WHEN 'HIGH' THEN 2 WHEN 'URGENT' THEN 3 ELSE 4 END", direction,
                            anchor == null || anchor.priority() == null ? 4
                                    : anchor.priority().ordinal()));
                }
                case ASSIGNEE -> {
                    terms.add(new SeekTerm("CASE WHEN assignee_user_id IS NULL THEN 1 ELSE 0 END",
                            "ASC", anchor != null && anchor.assigneeUserId() == null ? 1 : 0));
                    String expression = rankedUserExpression(parameters, "assignee_user_id",
                            "projectAssignee", ranks.assignees(), index);
                    terms.add(new SeekTerm(expression, direction, anchor == null ? 0
                            : ranks.assignees().getOrDefault(anchor.assigneeUserId(),
                                    ranks.assignees().size())));
                    terms.add(new SeekTerm("coalesce(assignee_user_id, "
                            + "'00000000-0000-0000-0000-000000000000'::uuid)", "ASC",
                            anchor == null || anchor.assigneeUserId() == null
                                    ? new UUID(0, 0) : anchor.assigneeUserId()));
                }
                case REPORTER -> {
                    String expression = rankedUserExpression(parameters, "reporter_user_id",
                            "projectReporter", ranks.reporters(), index);
                    terms.add(new SeekTerm(expression, direction, anchor == null ? 0
                            : ranks.reporters().getOrDefault(anchor.reporterUserId(),
                                    ranks.reporters().size())));
                    terms.add(new SeekTerm("reporter_user_id", "ASC",
                            anchor == null ? new UUID(0, 0) : anchor.reporterUserId()));
                }
                case TIMELINE_START_DATE -> addNullableDateTerms(terms, "timeline_start_date",
                        direction, anchor == null ? null : anchor.timelineStartDate());
                case TIMELINE_END_DATE -> addNullableDateTerms(terms, "timeline_end_date",
                        direction, anchor == null ? null : anchor.timelineEndDate());
                case DUE_DATE -> addNullableDateTerms(terms, "due_date", direction,
                        anchor == null ? null : anchor.dueDate());
                case UPDATED_AT -> terms.add(new SeekTerm("updated_at", direction,
                        anchor == null ? OffsetDateTime.MIN
                                : OffsetDateTime.ofInstant(anchor.updatedAt(), ZoneOffset.UTC)));
            }
            index++;
        }
        terms.add(new SeekTerm("id", "ASC", anchor == null ? new UUID(0, 0) : anchor.id()));
        return terms;
    }

    private static void addNullableDateTerms(List<SeekTerm> terms, String column,
            String direction, java.time.LocalDate value) {
        terms.add(new SeekTerm("CASE WHEN " + column + " IS NULL THEN 1 ELSE 0 END", "ASC",
                value == null ? 1 : 0));
        terms.add(new SeekTerm("coalesce(" + column + ", DATE '9999-12-31')", direction,
                value == null ? java.time.LocalDate.of(9999, 12, 31) : value));
    }

    private static String seekPredicate(List<SeekTerm> terms, Map<String, Object> parameters) {
        List<String> branches = new ArrayList<>();
        for (int index = 0; index < terms.size(); index++) {
            List<String> parts = new ArrayList<>();
            for (int prefix = 0; prefix < index; prefix++)
                parts.add(terms.get(prefix).expression() + " = :cursorSeek" + prefix);
            SeekTerm term = terms.get(index);
            parts.add(term.expression() + ("DESC".equals(term.direction()) ? " < " : " > ")
                    + ":cursorSeek" + index);
            branches.add("(" + String.join(" AND ", parts) + ")");
            parameters.put("cursorSeek" + index, term.anchorValue());
        }
        return String.join(" OR ", branches);
    }

    private static String rankedTextExpression(Map<String, Object> parameters, String column,
            String prefix, Map<String, Integer> ranks, int index) {
        StringBuilder expression = new StringBuilder("CASE ").append(column);
        ranks.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(entry -> {
            String name = prefix + index + "_" + entry.getValue();
            parameters.put(name, entry.getKey());
            expression.append(" WHEN :").append(name).append(" THEN ").append(entry.getValue());
        });
        return expression.append(" ELSE ").append(ranks.size()).append(" END").toString();
    }

    private static String rankedUserExpression(Map<String, Object> parameters, String column,
            String prefix, Map<UUID, Integer> ranks, int index) {
        StringBuilder expression = new StringBuilder("CASE ").append(column);
        ranks.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(entry -> {
            String name = prefix + index + "_" + entry.getValue();
            parameters.put(name, entry.getKey());
            expression.append(" WHEN :").append(name).append(" THEN ").append(entry.getValue());
        });
        return expression.append(" ELSE ").append(ranks.size()).append(" END").toString();
    }

    private static void addRankedTextOrder(List<String> order, Map<String, Object> parameters,
            String column, String prefix, Map<String, Integer> ranks, String direction, int index) {
        List<Map.Entry<String, Integer>> entries = ranks.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).toList();
        if (entries.isEmpty()) {
            order.add(column + " ASC");
            return;
        }
        String known = prefix + "Known" + index;
        parameters.put(known, entries.stream().map(Map.Entry::getKey).toList());
        order.add("CASE WHEN " + column + " IN (:" + known + ") THEN 0 ELSE 1 END ASC");
        StringBuilder rank = new StringBuilder("CASE ").append(column);
        for (int i = 0; i < entries.size(); i++) {
            String parameter = prefix + "Rank" + index + "_" + i;
            parameters.put(parameter, entries.get(i).getKey());
            rank.append(" WHEN :").append(parameter).append(" THEN ").append(i);
        }
        order.add(rank.append(" ELSE ").append(entries.size()).append(" END ")
                .append(direction).toString());
        order.add(column + " ASC");
    }

    private static void addRankedUserOrder(List<String> order, Map<String, Object> parameters,
            String column, String prefix, Map<UUID, Integer> ranks, String direction, int index) {
        List<Map.Entry<UUID, Integer>> entries = ranks.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).toList();
        if (entries.isEmpty()) {
            order.add("CASE WHEN " + column + " IS NULL THEN 1 ELSE 0 END ASC");
            order.add(column + " ASC NULLS LAST");
            return;
        }
        String known = prefix + "Known" + index;
        parameters.put(known, entries.stream().map(Map.Entry::getKey).toList());
        order.add("CASE WHEN " + column + " IN (:" + known + ") THEN 0 WHEN " + column
                + " IS NULL THEN 2 ELSE 1 END ASC");
        StringBuilder rank = new StringBuilder("CASE ").append(column);
        for (int i = 0; i < entries.size(); i++) {
            String parameter = prefix + "Rank" + index + "_" + i;
            parameters.put(parameter, entries.get(i).getKey());
            rank.append(" WHEN :").append(parameter).append(" THEN ").append(i);
        }
        order.add(rank.append(" ELSE ").append(entries.size()).append(" END ")
                .append(direction).toString());
        order.add(column + " ASC NULLS LAST");
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement,
            Map<String, Object> parameters) {
        JdbcClient.StatementSpec bound = statement;
        for (Map.Entry<String, Object> parameter : parameters.entrySet())
            bound = bound.param(parameter.getKey(), parameter.getValue());
        return bound;
    }

    private static WorkItem map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime deleted = rs.getObject("deleted_at", OffsetDateTime.class);
        return new WorkItem(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("content_id", UUID.class),
                rs.getLong("item_sequence"), rs.getString("item_no"),
                ContentWorkItemType.valueOf(rs.getString("type")), rs.getString("title"),
                rs.getString("status_code"), WorkItemStatusCategory.valueOf(rs.getString("status_category")),
                priority(rs.getString("priority")),
                rs.getObject("assignee_user_id", UUID.class), rs.getObject("reporter_user_id", UUID.class),
                rs.getString("description"), rs.getString("notes"), rs.getObject("timeline_start_date", java.time.LocalDate.class),
                rs.getObject("timeline_end_date", java.time.LocalDate.class), rs.getObject("due_date", java.time.LocalDate.class),
                rs.getString("rank"), rs.getString("project_sort_key"), rs.getLong("row_version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_by_user_id", UUID.class), rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_by_user_id", UUID.class), deleted == null ? null : deleted.toInstant(),
                rs.getObject("deleted_by_user_id", UUID.class), rs.getString("delete_reason"));
    }

    private static JdbcClient.StatementSpec nullable(JdbcClient.StatementSpec statement,
            String name, Object value, int sqlType) {
        return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
    }

    private static String priorityName(WorkItem item) {
        return item.priority() == null ? null : item.priority().name();
    }

    private static WorkItemPriority priority(String value) {
        return value == null ? null : WorkItemPriority.valueOf(value);
    }
}
