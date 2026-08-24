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
            row_version, created_at, created_by_user_id, updated_at, updated_by_user_id,
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
                    timeline_end_date, due_date, rank, row_version, created_at,
                    created_by_user_id, updated_at, updated_by_user_id, deleted_at,
                    deleted_by_user_id, delete_reason
                ) VALUES (
                    :id, :companyId, :projectId, :contentId, :itemSequence, :itemNo, :type,
                    :title, :statusCode, :statusCategory, :priority, :assigneeUserId,
                    :reporterUserId, :description, :notes, :timelineStartDate,
                    :timelineEndDate, :dueDate, :rank, :rowVersion, :createdAt,
                    :createdByUserId, :updatedAt, :updatedByUserId, :deletedAt,
                    :deletedByUserId, :deleteReason
                )
                """).param("id", item.id()).param("companyId", item.companyId())
                .param("projectId", item.projectId()).param("contentId", item.contentId())
                .param("itemSequence", item.itemSequence()).param("itemNo", item.itemNo())
                .param("type", item.type().name()).param("title", item.title())
                .param("statusCode", item.statusCode())
                .param("statusCategory", item.statusCategory().name())
                .param("priority", item.priority().name())
                .param("reporterUserId", item.reporterUserId())
                .param("rowVersion", item.rowVersion())
                .param("createdAt", OffsetDateTime.ofInstant(item.createdAt(), ZoneOffset.UTC))
                .param("createdByUserId", item.createdByUserId())
                .param("updatedAt", OffsetDateTime.ofInstant(item.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", item.updatedByUserId());
        statement = nullable(statement, "assigneeUserId", item.assigneeUserId(), Types.OTHER);
        statement = nullable(statement, "description", item.description(), Types.VARCHAR);
        statement = nullable(statement, "notes", item.notes(), Types.VARCHAR);
        statement = nullable(statement, "timelineStartDate", item.timelineStartDate(), Types.DATE);
        statement = nullable(statement, "timelineEndDate", item.timelineEndDate(), Types.DATE);
        statement = nullable(statement, "dueDate", item.dueDate(), Types.DATE);
        statement = nullable(statement, "rank", item.rank(), Types.VARCHAR);
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
                .param("priority", item.priority().name())
                .param("updatedAt", OffsetDateTime.ofInstant(item.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", item.updatedByUserId())
                .param("companyId", item.companyId()).param("projectId", item.projectId())
                .param("contentId", item.contentId()).param("id", item.id())
                .param("expectedVersion", expectedVersion);
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
                   SET rank=:rank, deleted_at=NULL, deleted_by_user_id=NULL, delete_reason=NULL,
                       row_version=row_version+1, updated_at=:updatedAt,
                       updated_by_user_id=:updatedByUserId
                 WHERE company_id=:companyId AND project_id=:projectId AND content_id=:contentId
                   AND id=:id AND deleted_at IS NOT NULL AND row_version=:expectedVersion
                RETURNING %s
                """.formatted(COLUMNS))
                .param("rank", item.rank())
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
    public List<WorkItem> findPage(UUID companyId, UUID projectId, UUID contentId,
            WorkItemQuery query, WorkItemSortRanks ranks, ContentViewType view,
            OffsetPageRequest page) {
        Map<String, Object> parameters = baseParameters(companyId, projectId, contentId);
        String where = where(query, parameters);
        String orderBy = view == ContentViewType.KANBAN
                ? "rank ASC, id ASC" : orderBy(query, ranks, parameters);
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
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("companyId", companyId);
        parameters.put("projectId", projectId);
        parameters.put("contentId", contentId);
        return parameters;
    }

    private static String where(WorkItemQuery query, Map<String, Object> parameters) {
        StringBuilder sql = new StringBuilder(" WHERE company_id=:companyId")
                .append(" AND project_id=:projectId AND content_id=:contentId")
                .append(" AND deleted_at IS NULL");
        if (query.query() != null) {
            sql.append(" AND lower(title) LIKE :titleQuery ESCAPE '\\'");
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
                case PRIORITY -> order.add("CASE priority WHEN 'LOW' THEN 0 WHEN 'MEDIUM' THEN 1 "
                        + "WHEN 'HIGH' THEN 2 WHEN 'URGENT' THEN 3 ELSE 4 END " + direction);
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
                WorkItemPriority.valueOf(rs.getString("priority")),
                rs.getObject("assignee_user_id", UUID.class), rs.getObject("reporter_user_id", UUID.class),
                rs.getString("description"), rs.getString("notes"), rs.getObject("timeline_start_date", java.time.LocalDate.class),
                rs.getObject("timeline_end_date", java.time.LocalDate.class), rs.getObject("due_date", java.time.LocalDate.class),
                rs.getString("rank"), rs.getLong("row_version"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_by_user_id", UUID.class), rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_by_user_id", UUID.class), deleted == null ? null : deleted.toInstant(),
                rs.getObject("deleted_by_user_id", UUID.class), rs.getString("delete_reason"));
    }

    private static JdbcClient.StatementSpec nullable(JdbcClient.StatementSpec statement,
            String name, Object value, int sqlType) {
        return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
    }
}
