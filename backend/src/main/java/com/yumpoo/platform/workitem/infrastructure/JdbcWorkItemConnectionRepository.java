package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.workitem.application.WorkItemConnectionRepository;
import com.yumpoo.platform.workitem.domain.WorkItemConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemConnectionModels.*;

@Repository
public class JdbcWorkItemConnectionRepository implements WorkItemConnectionRepository {
    private static final String SELECT = "SELECT r.*, c.name AS column_name, (c.deleted_at IS NULL) AS column_active ";
    private static final String FROM = """
            FROM yumpoo.work_item_connection r
            JOIN yumpoo.work_item_connect_column c ON c.id=r.column_id AND c.company_id=r.company_id
            """;
    private static final String ENDPOINTS = """
            JOIN yumpoo.work_item s ON s.id=r.source_work_item_id AND s.company_id=r.company_id
                AND s.project_id=r.source_project_id AND s.deleted_at IS NULL
            JOIN yumpoo.work_item t ON t.id=r.target_work_item_id AND t.company_id=r.company_id
                AND t.project_id=r.target_project_id AND t.deleted_at IS NULL
            """;
    private static final String ACTIVE = " r.deleted_at IS NULL AND c.deleted_at IS NULL ";
    private static final String VISIBLE = " (r.source_project_id IN (:visible) OR r.target_project_id IN (:visible)) ";
    private final JdbcClient jdbc;

    public JdbcWorkItemConnectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<ConnectionRow> find(UUID companyId, UUID connectionId) {
        return jdbc.sql(SELECT + ", 0 AS incoming_total " + FROM + " WHERE r.company_id=:companyId AND r.id=:id")
                .param("companyId", companyId).param("id", connectionId).query(JdbcWorkItemConnectionRepository::row).optional();
    }

    @Override
    public Optional<ConnectionRow> findReadable(UUID companyId, UUID connectionId, Set<UUID> visibleProjectIds) {
        if (visibleProjectIds.isEmpty()) return Optional.empty();
        return jdbc.sql(SELECT + ", 0 AS incoming_total " + FROM + ENDPOINTS
                        + " WHERE r.company_id=:companyId AND r.id=:id AND " + ACTIVE + " AND " + VISIBLE)
                .param("companyId", companyId).param("id", connectionId).param("visible", visibleProjectIds)
                .query(JdbcWorkItemConnectionRepository::row).optional();
    }

    @Override
    public Optional<WorkItemConnection> lock(UUID companyId, UUID connectionId) {
        return jdbc.sql("SELECT * FROM yumpoo.work_item_connection WHERE company_id=:companyId AND id=:id FOR UPDATE")
                .param("companyId", companyId).param("id", connectionId).query(JdbcWorkItemConnectionRepository::connection).optional();
    }

    @Override
    public Optional<WorkItemConnection> findActivePair(UUID companyId, UUID columnId, UUID sourceId, UUID targetId) {
        return jdbc.sql("""
                SELECT * FROM yumpoo.work_item_connection WHERE company_id=:companyId AND column_id=:column
                    AND source_work_item_id=:source AND target_work_item_id=:target AND deleted_at IS NULL
                """).param("companyId", companyId).param("column", columnId).param("source", sourceId)
                .param("target", targetId).query(JdbcWorkItemConnectionRepository::connection).optional();
    }

    @Override
    public boolean insert(WorkItemConnection connection) {
        return jdbc.sql("""
                INSERT INTO yumpoo.work_item_connection (id, company_id, column_id, source_project_id,
                    source_work_item_id, target_project_id, target_work_item_id, origin,
                    created_by_user_id, created_at, row_version)
                VALUES (:id, :companyId, :column, :sourceProject, :source, :targetProject, :target,
                    :origin, :actor, :now, 0)
                ON CONFLICT (company_id, column_id, source_work_item_id, target_work_item_id)
                    WHERE deleted_at IS NULL DO NOTHING
                """).param("id", connection.id()).param("companyId", connection.companyId())
                .param("column", connection.columnId()).param("sourceProject", connection.sourceProjectId())
                .param("source", connection.sourceWorkItemId()).param("targetProject", connection.targetProjectId())
                .param("target", connection.targetWorkItemId()).param("origin", connection.origin().name())
                .param("actor", connection.createdByUserId()).param("now", timestamp(connection.createdAt())).update() == 1;
    }

    @Override
    public boolean softDelete(WorkItemConnection connection, long expectedVersion) {
        return jdbc.sql("""
                UPDATE yumpoo.work_item_connection SET deleted_by_user_id=:actor, deleted_at=:now,
                    delete_reason=:reason, row_version=row_version+1
                WHERE company_id=:companyId AND id=:id AND row_version=:expected AND deleted_at IS NULL
                """).param("id", connection.id()).param("companyId", connection.companyId())
                .param("actor", connection.deletedByUserId()).param("now", timestamp(connection.deletedAt()))
                .param("reason", connection.deleteReason().name()).param("expected", expectedVersion).update() == 1;
    }

    @Override
    public long deleteColumnConnections(UUID companyId, UUID columnId, UUID actorId, Instant now) {
        return jdbc.sql("""
                UPDATE yumpoo.work_item_connection SET deleted_by_user_id=:actor, deleted_at=:now,
                    delete_reason='COLUMN_DELETED', row_version=row_version+1
                WHERE company_id=:companyId AND column_id=:column AND deleted_at IS NULL
                """).param("companyId", companyId).param("column", columnId).param("actor", actorId)
                .param("now", timestamp(now)).update();
    }

    @Override
    public long countActiveCell(UUID companyId, UUID columnId, UUID sourceId) {
        return jdbc.sql("""
                SELECT count(*) FROM yumpoo.work_item_connection
                WHERE company_id=:companyId AND column_id=:column AND source_work_item_id=:source AND deleted_at IS NULL
                """).param("companyId", companyId).param("column", columnId).param("source", sourceId).query(Long.class).single();
    }

    @Override
    public long countActiveTarget(UUID companyId, UUID columnId, UUID targetProjectId) {
        return jdbc.sql("""
                SELECT count(*) FROM yumpoo.work_item_connection
                WHERE company_id=:companyId AND column_id=:column AND target_project_id=:target AND deleted_at IS NULL
                """).param("companyId", companyId).param("column", columnId).param("target", targetProjectId).query(Long.class).single();
    }

    @Override
    public List<UUID> findActiveProjectItems(UUID companyId, UUID projectId, Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT id FROM yumpoo.work_item WHERE company_id=:companyId AND project_id=:projectId
                AND id IN (:ids) AND deleted_at IS NULL ORDER BY id
                """).param("companyId", companyId).param("projectId", projectId).param("ids", itemIds).query(UUID.class).list();
    }

    @Override
    public List<ConnectionRow> findOutgoing(UUID companyId, UUID projectId, Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) return List.of();
        return jdbc.sql(SELECT + ", 0 AS incoming_total " + FROM + ENDPOINTS
                        + " WHERE r.company_id=:companyId AND r.source_project_id=:projectId"
                        + " AND r.source_work_item_id IN (:ids) AND " + ACTIVE + " ORDER BY r.created_at, r.id")
                .param("companyId", companyId).param("projectId", projectId).param("ids", itemIds)
                .query(JdbcWorkItemConnectionRepository::row).list();
    }

    @Override
    public List<IncomingCellRow> findIncomingCells(UUID companyId, UUID projectId, Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM (" + SELECT + """
                , count(*) OVER (PARTITION BY r.target_work_item_id) AS incoming_total,
                count(*) OVER (PARTITION BY r.target_work_item_id, r.column_id) AS column_total,
                row_number() OVER (PARTITION BY r.target_work_item_id ORDER BY r.created_at, r.id) AS cell_position,
                row_number() OVER (PARTITION BY r.target_work_item_id, r.column_id ORDER BY r.created_at, r.id) AS position
                """ + FROM + ENDPOINTS + " WHERE r.company_id=:companyId AND r.target_project_id=:projectId"
                        + " AND r.target_work_item_id IN (:ids) AND " + ACTIVE
                        + ") ranked WHERE position<=50 ORDER BY target_work_item_id, cell_position")
                .param("companyId", companyId).param("projectId", projectId).param("ids", itemIds)
                .query((row, n) -> new IncomingCellRow(row(row, n), row.getLong("column_total"), row.getLong("cell_position")))
                .list();
    }

    @Override
    public Set<UUID> findIncomingProjectIds(UUID companyId, UUID workItemId) {
        return new LinkedHashSet<>(jdbc.sql("SELECT DISTINCT r.source_project_id " + FROM + ENDPOINTS
                        + " WHERE r.company_id=:companyId AND r.target_work_item_id=:id AND " + ACTIVE)
                .param("companyId", companyId).param("id", workItemId).query(UUID.class).list());
    }

    @Override
    public List<ConnectionRow> findIncomingPage(UUID companyId, UUID workItemId, UUID columnId,
            Set<UUID> visibleProjectIds, OffsetPageRequest page) {
        if (visibleProjectIds.isEmpty()) return List.of();
        return withColumn(jdbc.sql(SELECT + ", 0 AS incoming_total " + FROM + ENDPOINTS
                        + " WHERE r.company_id=:companyId AND r.target_work_item_id=:id AND " + ACTIVE
                        + " AND " + VISIBLE + columnFilter(columnId) + " ORDER BY r.created_at, r.id LIMIT :size OFFSET :offset"),
                columnId)
                .param("companyId", companyId).param("id", workItemId).param("visible", visibleProjectIds)
                .param("size", page.size()).param("offset", (long) page.page() * page.size())
                .query(JdbcWorkItemConnectionRepository::row).list();
    }

    @Override
    public long countIncoming(UUID companyId, UUID workItemId, UUID columnId, Set<UUID> visibleProjectIds) {
        if (visibleProjectIds.isEmpty()) return 0;
        return withColumn(jdbc.sql("SELECT count(*) " + FROM + ENDPOINTS
                        + " WHERE r.company_id=:companyId AND r.target_work_item_id=:id AND " + ACTIVE + " AND " + VISIBLE
                        + columnFilter(columnId)), columnId)
                .param("companyId", companyId).param("id", workItemId).param("visible", visibleProjectIds).query(Long.class).single();
    }

    private static String columnFilter(UUID columnId) { return columnId == null ? "" : " AND r.column_id=:column"; }

    private static JdbcClient.StatementSpec withColumn(JdbcClient.StatementSpec statement, UUID columnId) {
        return columnId == null ? statement : statement.param("column", columnId);
    }

    @Override
    public List<CardRow> findCards(UUID companyId, Collection<UUID> itemIds, boolean includingDeleted) {
        if (itemIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT w.id, w.project_id, w.item_no, w.title, w.archived, w.assignee_user_id,
                    s.status_code, s.display_name AS status_name, s.color_token AS status_color, s.status_category,
                    p.priority_code, p.display_name AS priority_name, p.color_token AS priority_color,
                    c.id AS content_id, c.name AS content_name, c.color_token AS content_color
                FROM yumpoo.work_item w
                JOIN yumpoo.project_work_item_status_label s ON s.company_id=w.company_id
                    AND s.project_id=w.project_id AND s.status_code=w.status_code
                LEFT JOIN yumpoo.project_work_item_priority_label p ON p.company_id=w.company_id
                    AND p.project_id=w.project_id AND p.priority_code=w.priority
                JOIN yumpoo.content c ON c.company_id=w.company_id AND c.project_id=w.project_id AND c.id=w.content_id
                WHERE w.company_id=:companyId AND w.id IN (:ids)
                """ + (includingDeleted ? "" : " AND w.deleted_at IS NULL"))
                .param("companyId", companyId).param("ids", itemIds).query(JdbcWorkItemConnectionRepository::card).list();
    }

    @Override
    public List<ConnectionCardCategory> findActiveCategories(UUID companyId, UUID projectId) {
        return jdbc.sql("""
                SELECT id, name, color_token FROM yumpoo.content
                WHERE company_id=:companyId AND project_id=:projectId AND active AND deleted_at IS NULL
                ORDER BY sort_order, id
                """).param("companyId", companyId).param("projectId", projectId)
                .query((row, n) -> new ConnectionCardCategory(row.getObject("id", UUID.class),
                        row.getString("name"), row.getString("color_token"))).list();
    }

    @Override
    public List<CandidateRow> findCandidates(UUID companyId, CandidateQuery query, OffsetPageRequest page) {
        String connected = query.anchorIsSource()
                ? "r.source_work_item_id=:anchor AND r.target_work_item_id=w.id"
                : "r.source_work_item_id=w.id AND r.target_work_item_id=:anchor";
        String order = query.sort() == CandidateSort.TITLE ? "lower(w.title), w.id" : "w.updated_at DESC, w.id";
        return candidateQuery("""
                SELECT w.id, parent.id AS parent_id, parent.title AS parent_title,
                    EXISTS(SELECT 1 FROM yumpoo.work_item_connection r WHERE r.company_id=w.company_id
                        AND r.column_id=:column AND %s AND r.deleted_at IS NULL) AS connected
                FROM yumpoo.work_item w
                LEFT JOIN yumpoo.work_item_relation relation ON relation.company_id=w.company_id
                    AND relation.right_work_item_id=w.id AND relation.relation_type='PARENT_CHILD'
                    AND relation.deleted_at IS NULL
                LEFT JOIN yumpoo.work_item parent ON parent.company_id=w.company_id
                    AND parent.id=relation.left_work_item_id AND parent.deleted_at IS NULL
                """.formatted(connected) + candidateWhere(query)
                        + " ORDER BY (lower(w.item_no)=lower(:exact)) DESC, " + order + " LIMIT :size OFFSET :offset",
                companyId, query).param("column", query.columnId()).param("anchor", query.anchorWorkItemId())
                .param("exact", query.text()).param("size", page.size()).param("offset", (long) page.page() * page.size())
                .query((row, n) -> new CandidateRow(row.getObject("id", UUID.class),
                        row.getObject("parent_id") == null ? null : new Parent(row.getObject("parent_id", UUID.class),
                                row.getString("parent_title")), row.getBoolean("connected"))).list();
    }

    @Override
    public long countCandidates(UUID companyId, CandidateQuery query) {
        return candidateQuery("SELECT count(*) FROM yumpoo.work_item w" + candidateWhere(query), companyId, query)
                .query(Long.class).single();
    }

    private static String candidateWhere(CandidateQuery query) {
        String where = " WHERE w.company_id=:companyId AND w.project_id=:projectId AND w.deleted_at IS NULL";
        if (query.text().isEmpty()) return where;
        List<String> matches = new ArrayList<>();
        if (query.fields().contains(CandidateField.NAME))
            matches.add("w.title ILIKE :query ESCAPE '!' OR w.item_no ILIKE :prefix ESCAPE '!'");
        if (query.fields().contains(CandidateField.STATUS)) matches.add("""
                EXISTS(SELECT 1 FROM yumpoo.project_work_item_status_label s WHERE s.company_id=w.company_id
                    AND s.project_id=w.project_id AND s.status_code=w.status_code AND s.display_name ILIKE :query ESCAPE '!')""");
        if (query.fields().contains(CandidateField.PRIORITY)) matches.add("""
                EXISTS(SELECT 1 FROM yumpoo.project_work_item_priority_label p WHERE p.company_id=w.company_id
                    AND p.project_id=w.project_id AND p.priority_code=w.priority AND p.display_name ILIKE :query ESCAPE '!')""");
        if (query.fields().contains(CandidateField.CONTENT)) matches.add("""
                EXISTS(SELECT 1 FROM yumpoo.content c WHERE c.company_id=w.company_id
                    AND c.project_id=w.project_id AND c.id=w.content_id AND c.name ILIKE :query ESCAPE '!')""");
        if (assigneeSearch(query)) matches.add("w.assignee_user_id IN (:assignees)");
        return where + " AND (" + (matches.isEmpty() ? "FALSE"
                : String.join(" OR ", matches.stream().map(match -> "(" + match + ")").toList())) + ")";
    }

    private static boolean assigneeSearch(CandidateQuery query) {
        return query.fields().contains(CandidateField.ASSIGNEE) && !query.assigneeIds().isEmpty();
    }

    private JdbcClient.StatementSpec candidateQuery(String sql, UUID companyId, CandidateQuery query) {
        var statement = jdbc.sql(sql).param("companyId", companyId).param("projectId", query.projectId());
        if (query.text().isEmpty()) return statement;
        String escaped = query.text().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        statement = statement.param("query", "%" + escaped + "%").param("prefix", escaped + "%");
        return assigneeSearch(query) ? statement.param("assignees", query.assigneeIds()) : statement;
    }

    private static ConnectionRow row(ResultSet row, int n) throws SQLException {
        return new ConnectionRow(connection(row, n), row.getString("column_name"),
                row.getBoolean("column_active"), row.getLong("incoming_total"));
    }

    private static WorkItemConnection connection(ResultSet row, int n) throws SQLException {
        return new WorkItemConnection(row.getObject("id", UUID.class), row.getObject("company_id", UUID.class),
                row.getObject("column_id", UUID.class), row.getObject("source_project_id", UUID.class),
                row.getObject("source_work_item_id", UUID.class), row.getObject("target_project_id", UUID.class),
                row.getObject("target_work_item_id", UUID.class), WorkItemConnection.Origin.valueOf(row.getString("origin")),
                row.getObject("created_by_user_id", UUID.class), row.getTimestamp("created_at").toInstant(),
                row.getObject("deleted_by_user_id", UUID.class),
                row.getTimestamp("deleted_at") == null ? null : row.getTimestamp("deleted_at").toInstant(),
                row.getString("delete_reason") == null ? null : WorkItemConnection.DeleteReason.valueOf(row.getString("delete_reason")),
                row.getLong("row_version"));
    }

    private static CardRow card(ResultSet row, int n) throws SQLException {
        return new CardRow(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
                row.getString("item_no"), row.getString("title"), row.getBoolean("archived"),
                new ConnectionCardStatus(row.getString("status_code"), row.getString("status_name"),
                        row.getString("status_color"), row.getString("status_category")),
                row.getString("priority_code") == null ? null : new ConnectionCardLabel(row.getString("priority_code"),
                        row.getString("priority_name"), row.getString("priority_color")),
                new ConnectionCardCategory(row.getObject("content_id", UUID.class), row.getString("content_name"),
                        row.getString("content_color")), row.getObject("assignee_user_id", UUID.class));
    }

    private static OffsetDateTime timestamp(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
}
