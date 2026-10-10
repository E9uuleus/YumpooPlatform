package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.filestorage.api.AttachmentLifecyclePort;
import com.yumpoo.platform.filestorage.api.AttachmentModels.ScanClaim;
import com.yumpoo.platform.filestorage.api.AttachmentModels.ScanOutcome;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AttachmentScanSchedulerTest {
    @Test
    void workerProvidesCorrelationForTransactionalAuditAndOutboxFinalization() throws InterruptedException {
        var attachments = mock(AttachmentLifecyclePort.class);
        var finalizer = mock(AttachmentFinalizationService.class);
        var claim = mock(ScanClaim.class);
        var outcome = new ScanOutcome.Clean("image/png", "sha256/test");
        var correlation = new AtomicReference<RequestCorrelation>();
        var finished = new CountDownLatch(1);
        when(attachments.claimDue(any(), any())).thenReturn(Optional.of(claim), Optional.empty());
        when(attachments.scan(claim)).thenReturn(outcome);
        doAnswer(call -> {
            try { correlation.set(RequestCorrelationContext.required()); }
            finally { finished.countDown(); }
            return null;
        }).when(finalizer).finalizeClean(claim, outcome);
        var scheduler = new AttachmentScanScheduler(attachments, finalizer, 1, Clock.systemUTC());
        try {
            scheduler.poll();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(correlation.get()).isNotNull();
            assertThat(correlation.get().requestId()).startsWith("attachment-processing-");
            verify(attachments, never()).retryProcessing(any(), any());
            assertThat(RequestCorrelationContext.current()).isEmpty();
        } finally { scheduler.destroy(); }
    }
}
