package com.yumpoo.platform.foundation.infrastructure.outbox;

import com.yumpoo.platform.foundation.application.outbox.OutboxTaskExecutor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class ThreadPoolOutboxTaskExecutor implements OutboxTaskExecutor, DisposableBean {

    private final ThreadPoolTaskExecutor delegate;

    ThreadPoolOutboxTaskExecutor(int concurrency, int queueCapacity) {
        delegate = new ThreadPoolTaskExecutor();
        delegate.setThreadNamePrefix("yumpoo-outbox-delivery-");
        delegate.setCorePoolSize(concurrency);
        delegate.setMaxPoolSize(concurrency);
        delegate.setQueueCapacity(queueCapacity);
        delegate.setWaitForTasksToCompleteOnShutdown(true);
        delegate.setAwaitTerminationSeconds(30);
        delegate.initialize();
    }

    @Override
    public CompletableFuture<Void> submit(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        delegate.execute(() -> {
            try {
                task.run();
                completion.complete(null);
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        });
        return completion;
    }

    @Override
    public void destroy() {
        delegate.shutdown();
    }
}
