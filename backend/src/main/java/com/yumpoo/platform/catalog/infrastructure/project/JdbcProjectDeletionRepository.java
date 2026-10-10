package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.project.ProjectDeletionRepository;
import com.yumpoo.platform.catalog.domain.project.Project;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProjectDeletionRepository implements ProjectDeletionRepository {
    private final JdbcClient jdbc;
    public JdbcProjectDeletionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<Project> find(UUID companyId, UUID projectId, boolean shareLock) {
        return jdbc.sql("SELECT * FROM yumpoo.project WHERE company_id=:company AND id=:project"
                        + (shareLock ? " FOR SHARE" : ""))
                .param("company", companyId).param("project", projectId).query(JdbcProjectRepository::map).optional();
    }

    public boolean purgingOrPurged(UUID companyId, UUID projectId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM yumpoo.project_purge_run WHERE company_id=:company AND project_id=:project)")
                .param("company", companyId).param("project", projectId).query(Boolean.class).single();
    }

    public Project schedule(Project project, UUID actorUserId, Instant now, Instant purgeAfter) {
        int changed = jdbc.sql("""
                UPDATE yumpoo.project SET deletion_requested_at=:now,deletion_requested_by=:actor,purge_after=:purgeAfter,
                    deletion_reminder_sent_at=NULL,row_version=row_version+1,updated_at=:now,updated_by_user_id=:actor
                 WHERE company_id=:company AND id=:project AND row_version=:version AND lifecycle='ARCHIVED'
                   AND deletion_requested_at IS NULL AND purge_started_at IS NULL
                """).param("now", utc(now)).param("actor", actorUserId).param("purgeAfter", utc(purgeAfter))
                .param("company", project.companyId()).param("project", project.id()).param("version", project.rowVersion()).update();
        if (changed != 1) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        return find(project.companyId(), project.id(), false).orElseThrow();
    }

    public Project cancel(Project project, UUID actorUserId, Instant now) {
        int changed = jdbc.sql("""
                UPDATE yumpoo.project SET deletion_requested_at=NULL,deletion_requested_by=NULL,purge_after=NULL,
                    deletion_reminder_sent_at=NULL,row_version=row_version+1,updated_at=:now,updated_by_user_id=:actor
                 WHERE company_id=:company AND id=:project AND row_version=:version AND lifecycle='ARCHIVED'
                   AND deletion_requested_at IS NOT NULL AND purge_started_at IS NULL
                """).param("now", utc(now)).param("actor", actorUserId).param("company", project.companyId())
                .param("project", project.id()).param("version", project.rowVersion()).update();
        if (changed != 1) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        return find(project.companyId(), project.id(), false).orElseThrow();
    }
    private static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
