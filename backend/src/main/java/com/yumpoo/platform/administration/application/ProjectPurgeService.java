package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.catalog.api.ProjectPurgeQueue;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Service
public class ProjectPurgeService {
    private final ProjectPurgeQueue queue;
    private final ProjectPurgeStages stages;
    private final ProjectDeletionSettings settings;
    private final TransactionalEventPort events;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper json;
    private final Clock clock;
    private final java.util.concurrent.atomic.AtomicInteger claims=new java.util.concurrent.atomic.AtomicInteger();
    public ProjectPurgeService(ProjectPurgeQueue queue,ProjectPurgeStages stages,ProjectDeletionSettings settings,
            TransactionalEventPort events,SecurityAuditAppendPort audits,ObjectMapper json,Clock clock) {
        this.queue=queue;this.stages=stages;this.settings=settings;this.events=events;
        this.audits=audits;this.json=json;this.clock=clock;
    }
    @Transactional
    public boolean remindOne() {
        var now=clock.instant();
        var due=queue.remindOne(now,settings.reminderLead());
        due.ifPresent(project->events.append(new EventDraft("catalog.project_deletion_reminder_due",1,"Project",
                project.projectId(),project.rowVersion(),project.companyId(),EventActor.system("PROJECT_PURGER"),
                json.valueToTree(Map.of("projectId",project.projectId(),"purgeAfter",project.purgeAfter(),"remindedAt",now)))));
        return due.isPresent();
    }
    public Optional<ProjectPurgeQueue.Lease> claim(String worker) {
        return queue.claim(clock.instant(),worker,Duration.ofMinutes(3),(claims.getAndIncrement()&1)==0);
    }
    @Transactional
    public boolean process(ProjectPurgeQueue.Lease lease) {
        if(!queue.hold(lease,clock.instant())) return false;
        boolean remaining=stages.provider(lease.stage()).purgeBatch(lease.companyId(),lease.projectId(),settings.batchSize());
        if(remaining) {
            requireOwned(queue.release(lease,clock.instant()));
            return true;
        }
        if("CATALOG".equals(lease.stage())) return true;
        requireOwned(queue.advance(lease,stages.next(lease.stage()),clock.instant()));
        return true;
    }
    private static void requireOwned(boolean owned) {
        if(!owned) throw new IllegalStateException("project purge lease expired during batch");
    }
    @Transactional
    public boolean complete(ProjectPurgeQueue.Lease lease) {
        var now=clock.instant();
        var version=queue.complete(lease,now);
        if(version.isEmpty()) return false;
        var payload=json.valueToTree(Map.of("projectId",lease.projectId(),"purgedAt",now));
        audits.append(new SecurityAuditDraft(lease.companyId(),"project-purged:"+lease.projectId(),"PROJECT_PURGED",
                SecurityAuditOutcome.SUCCEEDED,SecurityAuditActor.system("PROJECT_PURGER"),"PROJECT",lease.projectId().toString(),
                null,null,payload,null,null,null,null,now));
        events.append(new EventDraft("catalog.project_purged",1,"Project",lease.projectId(),version.get(),lease.companyId(),
                EventActor.system("PROJECT_PURGER"),payload));
        return true;
    }
}
