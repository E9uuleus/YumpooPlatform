package com.yumpoo.platform.filestorage.testing;

import com.yumpoo.platform.filestorage.application.AttachmentContentDetector;
import com.yumpoo.platform.filestorage.application.AttachmentFileName;
import com.yumpoo.platform.filestorage.application.DetectedAttachmentContent;
import com.yumpoo.platform.filestorage.infrastructure.TikaAttachmentContentDetector;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class M014ControllableContentDetector implements AttachmentContentDetector {
    private final AttachmentContentDetector delegate = new TikaAttachmentContentDetector();
    private volatile CountDownLatch entered = new CountDownLatch(1);
    private volatile CountDownLatch released = new CountDownLatch(0);
    private volatile boolean fail;

    @Override
    public DetectedAttachmentContent detect(Path sealedContent, AttachmentFileName fileName) throws IOException {
        entered.countDown();
        try {
            if (!released.await(30, TimeUnit.SECONDS)) throw new IOException("detector control timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("detector interrupted", exception);
        }
        if (fail) throw new IOException("controlled detector failure");
        return delegate.detect(sealedContent, fileName);
    }

    public void block() {
        reset();
        released = new CountDownLatch(1);
    }

    public boolean awaitDetectionEntered(Duration timeout) throws InterruptedException {
        return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void failDetection() { fail = true; }
    public void release() { released.countDown(); }

    public void reset() {
        release();
        entered = new CountDownLatch(1);
        released = new CountDownLatch(0);
        fail = false;
    }
}
