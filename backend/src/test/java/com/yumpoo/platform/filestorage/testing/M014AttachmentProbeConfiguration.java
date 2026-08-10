package com.yumpoo.platform.filestorage.testing;

import com.yumpoo.platform.filestorage.application.AttachmentSafetyProcessor;
import com.yumpoo.platform.filestorage.infrastructure.TikaAttachmentContentDetector;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@TestConfiguration(proxyBeanMethods = false)
public class M014AttachmentProbeConfiguration {

    @Bean(destroyMethod = "close")
    M014StorageFixture m014StorageFixture() throws IOException {
        return new M014StorageFixture();
    }

    @Bean
    M014ParentAccessResolver m014ParentAccessResolver() {
        return new M014ParentAccessResolver();
    }

    @Bean
    M014ControllableMalwareScanner m014ControllableMalwareScanner() {
        return new M014ControllableMalwareScanner();
    }

    @Bean
    AttachmentSafetyProcessor m014AttachmentSafetyProcessor(
            M014StorageFixture storageFixture,
            M014ControllableMalwareScanner scanner
    ) {
        return new AttachmentSafetyProcessor(
                storageFixture.storage(),
                new TikaAttachmentContentDetector(),
                scanner
        );
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService m014ScanExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8),
                Thread.ofPlatform().name("m014-scan-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean
    M014AttachmentProbeRepository m014AttachmentProbeRepository(
            org.springframework.jdbc.core.simple.JdbcClient jdbcClient,
            Clock clock
    ) {
        return new M014AttachmentProbeRepository(jdbcClient, clock);
    }

    @Bean
    M014AttachmentProbeService m014AttachmentProbeService(
            M014AttachmentProbeRepository repository,
            M014ParentAccessResolver accessResolver,
            M014StorageFixture storageFixture,
            AttachmentSafetyProcessor safetyProcessor,
            ExecutorService m014ScanExecutor
    ) {
        return new M014AttachmentProbeService(
                repository,
                accessResolver,
                storageFixture.storage(),
                safetyProcessor,
                m014ScanExecutor
        );
    }
}
