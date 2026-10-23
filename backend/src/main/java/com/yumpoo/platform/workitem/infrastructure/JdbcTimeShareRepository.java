package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.TimeShareRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.yumpoo.platform.workitem.application.TimeTrackingModels.Session;
import static com.yumpoo.platform.workitem.application.TimeShareCalculator.Share;

@Repository
public class JdbcTimeShareRepository implements TimeShareRepository {
    private final JdbcClient jdbc;
    public JdbcTimeShareRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    public Window snap(UUID companyId,UUID userId,Instant from,Instant to) {
        Instant start=jdbc.sql("SELECT started_at FROM yumpoo.work_item_time_share WHERE company_id=:c AND user_id=:u AND started_at<=:at AND (stopped_at IS NULL OR stopped_at>:at) ORDER BY started_at LIMIT 1")
                .param("c",companyId).param("u",userId).param("at",Timestamp.from(from)).query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(from);
        Instant stop=to;
        if(to!=null) {
            var ends=jdbc.sql("SELECT stopped_at FROM yumpoo.work_item_time_share WHERE company_id=:c AND user_id=:u AND started_at<:at AND (stopped_at IS NULL OR stopped_at>:at) ORDER BY stopped_at DESC NULLS FIRST LIMIT 1")
                    .param("c",companyId).param("u",userId).param("at",Timestamp.from(to))
                    .query((rs,n) -> new Window(null,rs.getTimestamp("stopped_at")==null ? null : rs.getTimestamp("stopped_at").toInstant())).list();
            if(!ends.isEmpty()) stop=ends.getFirst().to();
        }
        return new Window(start,stop);
    }

    public Set<UUID> projects(UUID companyId,UUID userId,Instant from,Instant to) {
        var query=jdbc.sql("SELECT DISTINCT project_id FROM yumpoo.work_item_time_share WHERE company_id=:c AND user_id=:u AND project_id IS NOT NULL AND started_at>=:from"+(to==null ? "" : " AND started_at<:to"))
                .param("c",companyId).param("u",userId).param("from",Timestamp.from(from));
        if(to!=null) query.param("to",Timestamp.from(to));
        return new HashSet<>(query.query(UUID.class).list());
    }
    public void delete(UUID companyId,UUID userId,Instant from,Instant to) {
        var query=jdbc.sql("DELETE FROM yumpoo.work_item_time_share WHERE company_id=:c AND user_id=:u AND started_at>=:from"+(to==null ? "" : " AND started_at<:to"))
                .param("c",companyId).param("u",userId).param("from",Timestamp.from(from));
        if(to!=null) query.param("to",Timestamp.from(to));
        query.update();
    }
    public List<Session> sessions(UUID companyId,UUID userId,Instant from,Instant to) {
        var query=jdbc.sql("SELECT * FROM yumpoo.work_item_time_session WHERE company_id=:c AND user_id=:u AND deleted_at IS NULL AND (stopped_at IS NULL OR stopped_at>started_at) AND (stopped_at IS NULL OR stopped_at>:from)"+(to==null ? "" : " AND started_at<:to"))
                .param("c",companyId).param("u",userId).param("from",Timestamp.from(from));
        if(to!=null) query.param("to",Timestamp.from(to));
        return query.query(JdbcTimeTrackingRepository::map).list();
    }
    public void insert(List<Share> shares) {
        for(Share s:shares) jdbc.sql("INSERT INTO yumpoo.work_item_time_share(session_id,started_at,stopped_at,company_id,user_id,project_id,work_item_id,concurrency,time_category) VALUES (:id,:start,:stop,:c,:u,:p,:w,:n,:category)")
                .param("id",s.sessionId()).param("start",Timestamp.from(s.startedAt())).param("stop",s.stoppedAt()==null ? null : Timestamp.from(s.stoppedAt()))
                .param("c",s.companyId()).param("u",s.userId()).param("p",s.projectId()).param("w",s.workItemId()).param("n",s.concurrency())
                .param("category",s.timeCategory()==null ? null : s.timeCategory().name()).update();
    }
}
