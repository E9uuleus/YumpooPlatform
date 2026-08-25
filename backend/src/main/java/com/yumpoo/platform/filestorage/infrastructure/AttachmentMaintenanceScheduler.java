package com.yumpoo.platform.filestorage.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

@Component
public final class AttachmentMaintenanceScheduler {
    private static final Logger LOGGER=LoggerFactory.getLogger(AttachmentMaintenanceScheduler.class);
    private final JdbcAttachmentMaintenanceService maintenance;
    private final MeterRegistry metrics;
    private final String workerId="attachment-maintenance@"+ManagementFactory.getRuntimeMXBean().getName();

    public AttachmentMaintenanceScheduler(JdbcAttachmentMaintenanceService maintenance,MeterRegistry metrics) {
        this.maintenance=maintenance; this.metrics=metrics;
    }

    @Scheduled(initialDelayString="${yumpoo.attachments.maintenance-initial-delay:5m}",
            fixedDelayString="${yumpoo.attachments.maintenance-poll-delay:1m}")
    public void resume() {
        maintenance.resumeOneBatch(workerId).ifPresent(result->{
            metrics.counter("yumpoo.attachments.maintenance.items","phase",result.phase()).increment(result.processed());
            metrics.counter("yumpoo.attachments.maintenance.issues","phase",result.phase()).increment(result.issues());
            metrics.counter("yumpoo.attachments.maintenance.deleted","phase",result.phase()).increment(result.deleted());
            LOGGER.info("attachment maintenance runId={} phase={} processed={} issues={} deleted={}",
                    result.runId(),result.phase(),result.processed(),result.issues(),result.deleted());
        });
    }
}
