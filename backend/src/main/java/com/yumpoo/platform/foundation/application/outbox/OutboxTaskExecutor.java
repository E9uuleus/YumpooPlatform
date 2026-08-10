package com.yumpoo.platform.foundation.application.outbox;

import java.util.concurrent.CompletableFuture;

public interface OutboxTaskExecutor {

    CompletableFuture<Void> submit(Runnable task);
}
