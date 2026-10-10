package com.yumpoo.platform.reporting.infrastructure;

import com.yumpoo.platform.foundation.application.purge.ProjectDataPurger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

@Component
public class JdbcReportingProjectDataPurger implements ProjectDataPurger {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public JdbcReportingProjectDataPurger(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public String stage() { return "REPORTING"; }
    public int order() { return 60; }
    private record Row(UUID id, String configuration) {}

    @Transactional
    public boolean purgeBatch(UUID companyId, UUID projectId, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("purge limit must be between 1 and 500");
        var rows = jdbc.sql("""
                SELECT id,configuration::text FROM yumpoo.personal_dashboard WHERE company_id=:company
                  AND jsonb_path_exists(configuration, '$.**.projectIds[*] ? (@ == $project)',
                        jsonb_build_object('project', CAST(:project AS text)))
                 ORDER BY id LIMIT :limit FOR UPDATE
                """).param("company", companyId).param("project", projectId).param("limit", limit)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2))).list();
        for (Row row : rows) {
            ObjectNode configuration = (ObjectNode) json.readTree(row.configuration());
            removeProjects(configuration, projectId.toString());
            jdbc.sql("""
                    UPDATE yumpoo.personal_dashboard SET configuration=CAST(:configuration AS jsonb),
                        row_version=row_version+1,updated_at=clock_timestamp() WHERE company_id=:company AND id=:id
                    """).param("configuration", json.writeValueAsString(configuration))
                    .param("company", companyId).param("id", row.id()).update();
        }
        return rows.size() == limit;
    }

    private void removeProjects(JsonNode configuration, String project) {
        if (configuration instanceof ObjectNode object) {
            if (object.get("projectIds") instanceof ArrayNode array) {
                ArrayNode kept = json.createArrayNode();
                for (JsonNode id : array) if (!project.equals(id.asText())) kept.add(id);
                object.set("projectIds", kept);
            }
            for (var property : object.properties()) removeProjects(property.getValue(), project);
        } else if (configuration instanceof ArrayNode array) {
            for (JsonNode child : array) removeProjects(child, project);
        }
    }
}
