package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.ProjectPurgeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class ProjectPurgeQueueAdapter implements ProjectPurgeQueue {
    private final ProjectPurgeRepository queue;
    public ProjectPurgeQueueAdapter(ProjectPurgeRepository queue) { this.queue=queue; }
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Reminder> remindOne(Instant now, Duration lead) {
        return queue.remindOne(now,lead).map(r->new Reminder(r.companyId(),r.projectId(),r.rowVersion(),r.purgeAfter()));
    }
    @Transactional
    public Optional<Lease> claim(Instant now,String worker,Duration duration,boolean newFirst) {
        return queue.claim(now,worker,duration,newFirst).map(r->new Lease(r.companyId(),r.projectId(),r.stage(),r.token(),r.until()));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean hold(Lease lease,Instant now) { return queue.hold(internal(lease),now); }
    @Transactional
    public boolean advance(Lease lease,String stage,Instant now) { return queue.advance(internal(lease),stage,now); }
    @Transactional
    public boolean release(Lease lease,Instant now) { return queue.release(internal(lease),now); }
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Long> complete(Lease lease,Instant now) { return queue.complete(internal(lease),now); }
    private static ProjectPurgeRepository.Lease internal(Lease lease) {
        return new ProjectPurgeRepository.Lease(lease.companyId(),lease.projectId(),lease.stage(),lease.token(),lease.until());
    }
}
