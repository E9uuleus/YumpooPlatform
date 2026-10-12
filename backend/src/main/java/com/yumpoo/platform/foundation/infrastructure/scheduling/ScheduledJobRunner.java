package com.yumpoo.platform.foundation.infrastructure.scheduling;

import com.yumpoo.platform.foundation.application.scheduling.ScheduledJobService;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ScheduledJobRunner implements SmartLifecycle {
    private final ScheduledJobService service;
    private final Environment environment;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public ScheduledJobRunner(ScheduledJobService service, Environment environment) {
        this.service = service;
        this.environment = environment;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "yumpoo-job-runner");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) executor.shutdownNow();
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public boolean isAutoStartup() {
        return environment.getProperty("yumpoo.jobs.enabled", Boolean.class, true);
    }

    @Override
    public int getPhase() { return Integer.MAX_VALUE - 100; }

    private void tick() {
        try {
            service.runDue();
        } catch (RuntimeException exception) {
            LoggerFactory.getLogger(getClass()).atWarn().setMessage("scheduled job tick failed")
                    .addKeyValue("event", "scheduler.tick.failed").setCause(exception).log();
        }
    }
}
