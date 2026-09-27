package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.WorkItemNotificationRepository;
import com.yumpoo.platform.workitem.application.WorkItemNotificationModels.*;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class JdbcWorkItemNotificationRepository implements WorkItemNotificationRepository {
    private final JdbcClient jdbc;
    public JdbcWorkItemNotificationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public Optional<Participants> participants(UUID company,UUID id) {
        return jdbc.sql("SELECT project_id,assignee_user_id,reporter_user_id FROM yumpoo.work_item WHERE company_id=:company AND id=:id AND deleted_at IS NULL")
                .param("company",company).param("id",id).query((rs,n)->new Participants(rs.getObject("project_id",UUID.class),
                        rs.getObject("assignee_user_id",UUID.class),rs.getObject("reporter_user_id",UUID.class))).optional();
    }
    public Optional<Update> update(UUID company,UUID id) {
        return jdbc.sql("""
            SELECT u.project_id,u.work_item_id,u.author_user_id,p.author_user_id AS parent_author
            FROM yumpoo.work_item_update u
            JOIN yumpoo.work_item w ON w.company_id=u.company_id AND w.id=u.work_item_id AND w.deleted_at IS NULL
            LEFT JOIN yumpoo.work_item_update p ON p.company_id=u.company_id AND p.id=u.parent_update_id AND p.status<>'DELETED'
            WHERE u.company_id=:company AND u.id=:id AND u.status<>'DELETED'
            """).param("company",company).param("id",id).query((rs,n)->new Update(rs.getObject("project_id",UUID.class),
                        rs.getObject("work_item_id",UUID.class),rs.getObject("author_user_id",UUID.class),rs.getObject("parent_author",UUID.class))).optional();
    }
    public Map<UUID,UUID> projects(UUID company,Collection<UUID> ids,boolean updates) {
        if(ids.isEmpty()) return Map.of();
        return jdbc.sql("SELECT id,project_id FROM yumpoo."+(updates?"work_item_update":"work_item")
                +" WHERE company_id=:company AND id IN (:ids) AND "+(updates?"status<>'DELETED'":"deleted_at IS NULL"))
                .param("company",company).param("ids",ids).query((rs,n)->Map.entry(rs.getObject("id",UUID.class),rs.getObject("project_id",UUID.class)))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,Map.Entry::getValue));
    }
    public Map<UUID,String> excerpts(UUID company,Collection<UUID> ids,Collection<UUID> projects) {
        if(ids.isEmpty() || projects.isEmpty()) return Map.of();
        return jdbc.sql("""
            SELECT u.id,left(regexp_replace(u.body_text,'\\s+',' ','g'),120) AS excerpt
            FROM yumpoo.work_item_update u JOIN yumpoo.work_item w ON w.id=u.work_item_id AND w.company_id=u.company_id
            WHERE u.company_id=:company AND u.id IN (:ids) AND u.project_id IN (:projects)
              AND u.status<>'DELETED' AND w.deleted_at IS NULL
            """)
                .param("company",company).param("ids",ids).param("projects",projects)
                .query((rs,n)->Map.entry(rs.getObject("id",UUID.class),rs.getString("excerpt")))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,Map.Entry::getValue));
    }
    public Map<UUID,Reference> references(UUID company,Collection<UUID> ids,Collection<UUID> projects) {
        if(ids.isEmpty() || projects.isEmpty()) return Map.of();
        return jdbc.sql("""
            SELECT w.id,w.project_id,w.content_id,c.name AS content_name,c.color_token,w.item_no,w.title,w.status_code,w.status_category
            FROM yumpoo.work_item w JOIN yumpoo.content c ON c.id=w.content_id AND c.company_id=w.company_id
            WHERE w.company_id=:company AND w.id IN (:ids) AND w.project_id IN (:projects) AND w.deleted_at IS NULL
            """).param("company",company).param("ids",ids).param("projects",projects)
                .query((rs,n)->new Reference(rs.getObject("id",UUID.class),rs.getObject("project_id",UUID.class),
                        rs.getObject("content_id",UUID.class),rs.getString("content_name"),rs.getString("color_token"),rs.getString("item_no"),
                        rs.getString("title"),rs.getString("status_code"),rs.getString("status_category"),false))
                .list().stream().collect(Collectors.toUnmodifiableMap(Reference::workItemId,x->x));
    }
}
