package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.project.MemberProjectRepository;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcMemberProjectRepository implements MemberProjectRepository {
    private static final String SCOPE = """
        FROM yumpoo.project p JOIN yumpoo.project_membership m
          ON m.company_id=p.company_id AND m.project_id=p.id AND m.user_id=:user AND m.status='ACTIVE'
        WHERE p.company_id=:company
        """;
    private static final String SEARCH = """
        AND (:archived OR p.lifecycle<>'ARCHIVED')
        AND (:empty OR p.name ILIKE :query ESCAPE '\\' OR p.project_code ILIKE :query ESCAPE '\\')
        """;
    private final JdbcClient jdbc;
    public JdbcMemberProjectRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Project> find(CurrentActor actor, Collection<UUID> ids) {
        return projects(scope("SELECT p.id,p.name,p.project_code,p.lifecycle " + SCOPE + " AND p.id IN (:ids)", actor)
                .param("ids", ids));
    }
    public List<Project> search(CurrentActor actor, String query, boolean archived, int offset, int limit) {
        return projects(search("SELECT p.id,p.name,p.project_code,p.lifecycle " + SCOPE + SEARCH
                + " ORDER BY p.name,p.id LIMIT :limit OFFSET :offset", actor, query, archived)
                .param("limit", limit).param("offset", offset));
    }
    public long count(CurrentActor actor, String query, boolean archived) {
        return search("SELECT COUNT(*) " + SCOPE + SEARCH, actor, query, archived).query(Long.class).single();
    }
    private JdbcClient.StatementSpec scope(String sql, CurrentActor actor) {
        return jdbc.sql(sql).param("company", actor.companyId()).param("user", actor.userId());
    }
    private JdbcClient.StatementSpec search(String sql, CurrentActor actor, String query, boolean archived) {
        return scope(sql, actor).param("archived", archived).param("empty", query.isEmpty())
                .param("query", "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    }
    private static List<Project> projects(JdbcClient.StatementSpec statement) {
        return statement.query((rs, n) -> new Project(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("project_code"), rs.getString("lifecycle"))).list();
    }
}
