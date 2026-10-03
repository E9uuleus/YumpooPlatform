package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.WorkItemQuery.ConnectionFilter;
import java.util.Map;
import java.util.UUID;

final class JdbcConnectionFilterSql {
    private JdbcConnectionFilterSql() {}

    static String activeConnections(boolean incoming) {
        String local = incoming ? "target" : "source";
        String other = incoming ? "source" : "target";
        return " FROM yumpoo.work_item_connection connection"
                + " JOIN yumpoo.work_item_connect_column connect_column ON connect_column.id=connection.column_id"
                + " AND connect_column.company_id=connection.company_id AND connect_column.deleted_at IS NULL"
                + " JOIN yumpoo.work_item counterpart ON counterpart.id=connection." + other + "_work_item_id"
                + " AND counterpart.company_id=connection.company_id"
                + " AND counterpart.project_id=connection." + other + "_project_id AND counterpart.deleted_at IS NULL"
                + " WHERE connection.company_id=:companyId AND connection." + local + "_project_id=:projectId"
                + " AND connection." + local + "_work_item_id=yumpoo.work_item.id AND connection.deleted_at IS NULL";
    }

    static void append(StringBuilder sql, Map<String, Object> parameters, ConnectionFilter filter) {
        int index = 0;
        for (UUID id : filter.connectedColumnIds().stream().sorted().toList()) {
            String key = "connectedColumn" + index++;
            sql.append(" AND EXISTS (SELECT 1").append(activeConnections(false))
                    .append(" AND connection.column_id=:").append(key).append(")");
            parameters.put(key, id);
        }
        index = 0;
        for (UUID id : filter.unconnectedColumnIds().stream().sorted().toList()) {
            String key = "unconnectedColumn" + index++;
            sql.append(" AND EXISTS (SELECT 1 FROM yumpoo.work_item_connect_column selected_column")
                    .append(" WHERE selected_column.company_id=:companyId AND selected_column.project_id=:projectId")
                    .append(" AND selected_column.deleted_at IS NULL AND selected_column.id=:").append(key).append(")")
                    .append(" AND NOT EXISTS (SELECT 1").append(activeConnections(false))
                    .append(" AND connection.column_id=:").append(key).append(")");
            parameters.put(key, id);
        }
        if (!filter.incomingProjectIds().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1").append(activeConnections(true))
                    .append(" AND connection.source_project_id IN (:incomingProjectIds))");
            parameters.put("incomingProjectIds", filter.incomingProjectIds());
        }
    }
}
