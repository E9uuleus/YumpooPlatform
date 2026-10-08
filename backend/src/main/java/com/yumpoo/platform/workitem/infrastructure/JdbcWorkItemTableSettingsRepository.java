package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.WorkItemTableSettingsModels.Write;
import com.yumpoo.platform.workitem.application.WorkItemTableSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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

    @Override public Optional<Stored> save(UUID company, UUID project, UUID user, Write settings, long expectedVersion) {
        if (expectedVersion == 0) return jdbc.sql("""
            INSERT INTO yumpoo.work_item_table_settings (company_id,project_id,user_id,settings)
            VALUES (:company,:project,:user,CAST(:settings AS jsonb))
            ON CONFLICT (company_id,project_id,user_id) DO NOTHING
            RETURNING settings, updated_at
            """).param("company", company).param("project", project).param("user", user)
                .param("settings", json.writeValueAsString(settings)).query(this::map).optional();
        Instant expected = Instant.ofEpochSecond(expectedVersion / 1_000_000, (expectedVersion % 1_000_000) * 1_000);
        return jdbc.sql("""
            UPDATE yumpoo.work_item_table_settings
            SET settings=CAST(:settings AS jsonb), updated_at=GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
            WHERE company_id=:company AND project_id=:project AND user_id=:user AND updated_at=:expected
            RETURNING settings, updated_at
            """).param("company", company).param("project", project).param("user", user)
                .param("expected", Timestamp.from(expected)).param("settings", json.writeValueAsString(settings))
                .query(this::map).optional();
    }

    private Stored map(ResultSet rs, int row) throws SQLException {
        return new Stored(json.readValue(rs.getString("settings"), Write.class), rs.getTimestamp("updated_at").toInstant());
    }
}
