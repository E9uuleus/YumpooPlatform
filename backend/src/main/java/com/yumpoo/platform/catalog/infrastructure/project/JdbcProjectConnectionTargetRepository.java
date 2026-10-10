package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.project.ProjectConnectionTargetRepository;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcProjectConnectionTargetRepository implements ProjectConnectionTargetRepository {
    private static final String ACTIVE = """
            FROM yumpoo.project WHERE company_id=:companyId AND lifecycle='ACTIVE' AND purge_started_at IS NULL
              AND (name ILIKE :query ESCAPE '!' OR project_code ILIKE :query ESCAPE '!')
            """;
    private final JdbcClient jdbc;

    public JdbcProjectConnectionTargetRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Target> searchActive(UUID companyId, String query, OffsetPageRequest page) {
        return search("SELECT id, project_code, name, lifecycle, purge_started_at " + ACTIVE
                        + " ORDER BY name, project_code, id LIMIT :size OFFSET :offset", companyId, query)
                .param("size", page.size()).param("offset", (long) page.page() * page.size())
                .query(JdbcProjectConnectionTargetRepository::map).list();
    }

    @Override
    public long countActive(UUID companyId, String query) {
        return search("SELECT count(*) " + ACTIVE, companyId, query).query(Long.class).single();
    }

    @Override
    public List<Target> findByIds(UUID companyId, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT id, project_code, name, lifecycle, purge_started_at FROM yumpoo.project
                WHERE company_id=:companyId AND id IN (:ids)
                """).param("companyId", companyId).param("ids", ids)
                .query(JdbcProjectConnectionTargetRepository::map).list();
    }

    private JdbcClient.StatementSpec search(String sql, UUID companyId, String query) {
        return jdbc.sql(sql).param("companyId", companyId).param("query",
                "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    }

    private static Target map(ResultSet row, int number) throws SQLException {
        return new Target(row.getObject("id", UUID.class), row.getString("project_code"),
                row.getString("name"), row.getString("lifecycle"),row.getTimestamp("purge_started_at")!=null);
    }
}
