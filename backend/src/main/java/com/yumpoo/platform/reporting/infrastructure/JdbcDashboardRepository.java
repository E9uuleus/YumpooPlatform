package com.yumpoo.platform.reporting.infrastructure;

import com.yumpoo.platform.reporting.application.DashboardRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.yumpoo.platform.reporting.application.DashboardModels.*;

@Repository
public class JdbcDashboardRepository implements DashboardRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public JdbcDashboardRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public List<Stored> list(UUID company, UUID owner) {
        return jdbc.sql("SELECT * FROM yumpoo.personal_dashboard WHERE company_id=:company AND owner_user_id=:owner AND deleted_at IS NULL ORDER BY created_at,id")
                .param("company", company).param("owner", owner).query(this::map).list();
    }
    public Optional<Stored> find(UUID company, UUID owner, UUID id, boolean includeDeleted) {
        return jdbc.sql("SELECT * FROM yumpoo.personal_dashboard WHERE company_id=:company AND owner_user_id=:owner AND id=:id"
                + (includeDeleted ? "" : " AND deleted_at IS NULL"))
                .param("company", company).param("owner", owner).param("id", id).query(this::map).optional();
    }
    public void insert(Stored d) {
        params("""
            INSERT INTO yumpoo.personal_dashboard(id,company_id,owner_user_id,name,configuration,row_version,created_at,updated_at)
            VALUES(:id,:company,:owner,:name,CAST(:configuration AS jsonb),:version,:created,:updated)
            """, d).update();
    }
    public boolean update(Stored d, long expected) {
        return params("""
            UPDATE yumpoo.personal_dashboard SET name=:name,configuration=CAST(:configuration AS jsonb),row_version=:version,updated_at=:updated
            WHERE id=:id AND company_id=:company AND owner_user_id=:owner AND row_version=:expected AND deleted_at IS NULL
            """, d).param("expected", expected).update() == 1;
    }
    public boolean delete(UUID company, UUID owner, UUID id, long expected, Instant now) {
        return jdbc.sql("""
            UPDATE yumpoo.personal_dashboard SET deleted_at=:now,updated_at=:now,row_version=row_version+1
            WHERE id=:id AND company_id=:company AND owner_user_id=:owner AND row_version=:expected AND deleted_at IS NULL
            """).param("company", company).param("owner", owner).param("id", id).param("expected", expected).param("now", Timestamp.from(now)).update() == 1;
    }
    private JdbcClient.StatementSpec params(String sql, Stored d) {
        return jdbc.sql(sql).param("id", d.id()).param("company", d.companyId()).param("owner", d.ownerUserId())
                .param("name", d.name()).param("configuration", json.writeValueAsString(d.configuration())).param("version", d.version())
                .param("created", Timestamp.from(d.createdAt())).param("updated", Timestamp.from(d.updatedAt()));
    }
    private Stored map(ResultSet rs, int row) throws SQLException {
        Timestamp deleted = rs.getTimestamp("deleted_at");
        return new Stored(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                rs.getString("name"), json.readValue(rs.getString("configuration"), Configuration.class), rs.getLong("row_version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), deleted == null ? null : deleted.toInstant());
    }
}
