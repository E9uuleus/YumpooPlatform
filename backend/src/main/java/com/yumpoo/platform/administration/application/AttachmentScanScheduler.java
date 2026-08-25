package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.filestorage.api.AttachmentLifecyclePort;
import com.yumpoo.platform.filestorage.api.AttachmentModels.ScanClaim;
import com.yumpoo.platform.filestorage.api.AttachmentModels.ScanOutcome;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(prefix="yumpoo.attachments",name="scan-enabled",matchIfMissing=true)
public class AttachmentScanScheduler implements DisposableBean {
    private final AttachmentLifecyclePort attachments;
    private final AttachmentFinalizationService finalizer;
    private final Clock clock;
    private final String workerId="attachment-"+UUID.randomUUID();
    private final ExecutorService executor;
    private final AtomicInteger inFlight=new AtomicInteger();
    private final int concurrency;

    public AttachmentScanScheduler(AttachmentLifecyclePort attachments,
            AttachmentFinalizationService finalizer,
            @Value("${yumpoo.attachments.scan-concurrency:2}") int concurrency, Clock clock) {
        this.attachments=attachments; this.finalizer=finalizer; this.clock=clock;
        this.concurrency=concurrency;
        this.executor=Executors.newFixedThreadPool(concurrency,
                Thread.ofPlatform().name("yumpoo-attachment-scan-",0).factory());
    }

    @Scheduled(fixedDelayString="${yumpoo.attachments.poll-delay:500ms}",
            initialDelayString="${yumpoo.attachments.initial-delay:1s}")
    public void poll() {
        while(inFlight.get()<concurrency) {
            var claim=attachments.claimDue(workerId,clock.instant());
            if(claim.isEmpty()) return;
            inFlight.incrementAndGet();
            executor.execute(()->process(claim.orElseThrow()));
        }
    }

    private void process(ScanClaim claim) {
        try {
            ScanOutcome outcome=attachments.scan(claim);
            if(outcome instanceof ScanOutcome.Clean clean) finalizer.finalizeClean(claim,clean);
            else if(outcome instanceof ScanOutcome.Rejected rejected)
                finalizer.finalizeRejected(claim,rejected.code());
            else attachments.retryOrExhaust(claim,clock.instant());
        } catch (RuntimeException failure) {
            attachments.retryOrExhaust(claim,clock.instant());
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Override public void destroy() { executor.shutdownNow(); }
}
