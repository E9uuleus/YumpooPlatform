package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.WorkItemTableSettingsModels.Write;
import com.yumpoo.platform.workitem.application.WorkItemTableSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkItemTableSettingsRepository implements WorkItemTableSettingsRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public JdbcWorkItemTableSettingsRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override public Optional<Stored> find(UUID company, UUID project, UUID user) {
        return jdbc.sql("""
            SELECT settings, updated_at FROM yumpoo.work_item_table_settings
            WHERE company_id=:company AND project_id=:project AND user_id=:user
            """).param("company", company).param("project", project).param("user", user)
                .query(this::map).optional();
    }

    @Override public Stored save(UUID company, UUID project, UUID user, Write settings) {
        return jdbc.sql("""
            INSERT INTO yumpoo.work_item_table_settings (company_id,project_id,user_id,settings)
            VALUES (:company,:project,:user,CAST(:settings AS jsonb))
            ON CONFLICT (company_id,project_id,user_id) DO UPDATE SET settings=EXCLUDED.settings,
              updated_at=clock_timestamp()
            RETURNING settings, updated_at
            """).param("company", company).param("project", project).param("user", user)
                .param("settings", json.writeValueAsString(settings)).query(this::map).single();
    }

    private Stored map(ResultSet rs, int row) throws SQLException {
        return new Stored(json.readValue(rs.getString("settings"), Write.class), rs.getTimestamp("updated_at").toInstant());
    }
}
