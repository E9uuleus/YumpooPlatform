package com.yumpoo.platform.administration.infrastructure;

import com.yumpoo.platform.administration.application.ProjectPurgeService;
import com.yumpoo.platform.catalog.api.ProjectPurgeQueue;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.lang.management.ManagementFactory;
import java.util.UUID;

@Component
public final class ProjectPurgeScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(ProjectPurgeScheduler.class);
    private final ProjectPurgeService service;
    private final MeterRegistry metrics;
    private final String worker="project-purge@"+ManagementFactory.getRuntimeMXBean().getName();
    public ProjectPurgeScheduler(ProjectPurgeService service,MeterRegistry metrics) { this.service=service;this.metrics=metrics; }
    @Scheduled(initialDelayString="${yumpoo.projects.deletion.purge-poll-delay:1m}",
            fixedDelayString="${yumpoo.projects.deletion.purge-poll-delay:1m}")
    public void poll() {
        String phase="REMIND";
        ProjectPurgeQueue.Lease current=null;
        try(var context=RequestCorrelationContext.open(RequestCorrelation.root(UUID.randomUUID().toString()))) {
            service.remindOne();
            phase="CLAIM";
            var lease=service.claim(worker);
            if(lease.isEmpty()) return;
            current=lease.get();
            phase=current.stage();
            boolean processed=service.process(current);
            if(processed && "CATALOG".equals(current.stage())) {
                phase="COMPLETE";
                service.complete(current);
            }
            metrics.counter("yumpoo.projects.purge.batches","stage",current.stage()).increment();
        } catch(RuntimeException failure) {
            metrics.counter("yumpoo.projects.purge.failures","phase",phase).increment();
            var log=LOG.atWarn().setMessage("project purge batch failed; durable lease will allow retry").setCause(failure)
                    .addKeyValue("event","project.purge.failed").addKeyValue("phase",phase)
                    .addKeyValue("exceptionType",rootExceptionType(failure));
            if(current!=null) log=log.addKeyValue("projectId",current.projectId());
            log.log();
        }
    }

    static String rootExceptionType(Throwable failure) {
        Throwable cause=failure;
        while(cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
        String name=cause.getClass().getName();
        return name.length()<=160?name:name.substring(0,160);
    }
}
