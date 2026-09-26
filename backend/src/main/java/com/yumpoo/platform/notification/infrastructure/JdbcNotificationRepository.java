package com.yumpoo.platform.notification.infrastructure;

import com.yumpoo.platform.notification.application.NotificationRepository;
import com.yumpoo.platform.notification.application.NotificationModels.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {
    private final JdbcClient jdbc;
    public JdbcNotificationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    @Override public Instant acceptedFrom() {
        return jdbc.sql("SELECT accepted_from FROM yumpoo.notification_projection_state WHERE projection_code='INBOX_V1'")
                .query(OffsetDateTime.class).single().toInstant();
    }
    @Override public Instant serverNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }
    @Override public void append(Event e,Map<UUID,Reason> recipients) {
        jdbc.sql("""
            INSERT INTO yumpoo.notification_event
            (id,company_id,source_event_id,event_type,payload_schema_version,target_kind,project_id,
             work_item_id,update_id,subject_user_id,actor_user_id,occurred_at)
            VALUES (:id,:company,:source,:type,:version,:kind,:project,:item,:update,:subject,:actor,:occurred)
            ON CONFLICT DO NOTHING
            """).param("id",e.id()).param("company",e.companyId()).param("source",e.sourceEventId())
                .param("type",e.eventType()).param("version",e.version()).param("kind",e.kind().name())
                .param("project",e.projectId()).param("item",e.workItemId(),Types.OTHER)
                .param("update",e.updateId(),Types.OTHER).param("subject",e.subjectUserId(),Types.OTHER)
                .param("actor",e.actorUserId(),Types.OTHER).param("occurred",time(e.occurredAt())).update();
        UUID eventId=jdbc.sql("SELECT id FROM yumpoo.notification_event WHERE source_event_id=:source AND company_id=:company")
                .param("source",e.sourceEventId()).param("company",e.companyId()).query(UUID.class).single();
        recipients.forEach((user,reason)->jdbc.sql("""
            INSERT INTO yumpoo.user_notification (id,company_id,notification_event_id,recipient_user_id,reason)
            VALUES (:id,:company,:event,:user,:reason) ON CONFLICT DO NOTHING
            """).param("id",UUID.randomUUID()).param("company",e.companyId()).param("event",eventId)
                .param("user",user).param("reason",reason.name()).update());
    }
    @Override public List<Row> find(UUID company,UUID user,ListState state,Group group,Anchor before,int limit) {
        String stateSql=switch(state) { case ALL->"n.state <> 'ARCHIVED'"; case UNREAD->"n.state='UNREAD'"; case ARCHIVED->"n.state='ARCHIVED'"; };
        var query=jdbc.sql("""
            SELECT n.id AS notification_id,n.reason,n.state,n.created_at AS notification_created_at,n.read_at,e.*
            FROM yumpoo.user_notification n JOIN yumpoo.notification_event e ON e.id=n.notification_event_id AND e.company_id=n.company_id
            WHERE n.company_id=:company AND n.recipient_user_id=:user AND
            """+stateSql+groupSql(group,"n.")+(before==null?"":" AND (n.created_at,n.id)<(:beforeAt,:beforeId)")
                +" ORDER BY n.created_at DESC,n.id DESC LIMIT :limit")
                .param("company",company).param("user",user).param("limit",limit);
        if(before!=null) query=query.param("beforeAt",time(before.createdAt())).param("beforeId",before.id());
        return query.query((rs,n)->new Row(rs.getObject("notification_id",UUID.class),Reason.valueOf(rs.getString("reason")),
                State.valueOf(rs.getString("state")),instant(rs,"notification_created_at"),instant(rs,"read_at"),
                new Event(rs.getObject("id",UUID.class),company,rs.getObject("source_event_id",UUID.class),rs.getString("event_type"),
                        rs.getInt("payload_schema_version"),TargetKind.valueOf(rs.getString("target_kind")),
                        rs.getObject("project_id",UUID.class),rs.getObject("work_item_id",UUID.class),rs.getObject("update_id",UUID.class),
                        rs.getObject("subject_user_id",UUID.class),rs.getObject("actor_user_id",UUID.class),instant(rs,"occurred_at")))).list();
    }
    @Override public UnreadCounts counts(UUID company,UUID user) {
        return jdbc.sql("""
            SELECT count(*) AS total,count(*) FILTER(WHERE reason='MENTION') AS mention,
              count(*) FILTER(WHERE reason IN ('COMMENT','REPLY')) AS comment,
              count(*) FILTER(WHERE reason='ASSIGNED') AS assigned,
              count(*) FILTER(WHERE reason LIKE 'PROJECT_%') AS project,
              max(created_at) AS newest,clock_timestamp() AS now
            FROM yumpoo.user_notification WHERE company_id=:company AND recipient_user_id=:user AND state='UNREAD'
            """).param("company",company).param("user",user).query((rs,n)->new UnreadCounts(rs.getLong("total"),rs.getLong("mention"),
                rs.getLong("comment"),rs.getLong("assigned"),rs.getLong("project"),instant(rs,"newest"),instant(rs,"now"))).single();
    }
    @Override public boolean setState(UUID company,UUID user,UUID id,State state) {
        String timestamps=switch(state) {
            case READ->"read_at=COALESCE(read_at,clock_timestamp())";
            case UNREAD->"read_at=NULL,archived_at=NULL";
            case ARCHIVED->"archived_at=COALESCE(archived_at,clock_timestamp())";
        };
        String nextState=state==State.READ?"CASE WHEN state='ARCHIVED' THEN state ELSE :state END":":state";
        return jdbc.sql("UPDATE yumpoo.user_notification SET state="+nextState+","+timestamps+",updated_at=clock_timestamp()"
                +" WHERE company_id=:company AND recipient_user_id=:user AND id=:id")
                .param("state",state.name()).param("company",company).param("user",user).param("id",id).update()>0;
    }
    @Override public void readAll(UUID company,UUID user,Instant upTo,Group group) {
        jdbc.sql("UPDATE yumpoo.user_notification SET state='READ',read_at=clock_timestamp(),updated_at=clock_timestamp()"
                +" WHERE company_id=:company AND recipient_user_id=:user AND state='UNREAD' AND created_at<=:upTo"+groupSql(group,""))
                .param("company",company).param("user",user).param("upTo",time(upTo)).update();
    }
    private static String groupSql(Group group,String prefix) {
        if(group==null) return "";
        return " AND "+prefix+switch(group) {
            case MENTION->"reason='MENTION'"; case COMMENT->"reason IN ('COMMENT','REPLY')";
            case ASSIGNED->"reason='ASSIGNED'"; case PROJECT->"reason LIKE 'PROJECT_%'";
        };
    }
    private static OffsetDateTime time(Instant time) { return time.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs,String name) throws SQLException {
        var value=rs.getObject(name,OffsetDateTime.class); return value==null?null:value.toInstant();
    }
}
