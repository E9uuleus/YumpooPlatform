package com.yumpoo.platform.foundation.infrastructure.logging;

import org.flywaydb.core.Flyway;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ApplicationLogging {

    private final ObjectProvider<BuildProperties> build;
    private final ObjectProvider<Flyway> flyway;
    private final Environment environment;

    public ApplicationLogging(
        ObjectProvider<BuildProperties> build,
        ObjectProvider<Flyway> flyway,
        Environment environment
    ) {
        this.build = build;
        this.flyway = flyway;
        this.environment = environment;
    }

    @EventListener
    public void ready(ApplicationReadyEvent event) {
        var properties = build.getIfAvailable();
        String schema = "unknown";
        try {
            var migration = flyway.getIfAvailable();
            if (migration != null && migration.info().current() != null) schema = migration
                .info()
                .current()
                .getVersion()
                .toString();
        } catch (RuntimeException ignored) {}
        LoggerFactory.getLogger(getClass())
            .atInfo()
            .setMessage("application started")
            .addKeyValue("event", "app.started")
            .addKeyValue("release", environment.getProperty("info.release.tag", "unreleased"))
            .addKeyValue("commit", properties == null ? "unknown" : properties.get("commit"))
            .addKeyValue("schemaVersion", schema)
            .addKeyValue("startupMs", event.getTimeTaken() == null ? 0 : event.getTimeTaken().toMillis())
            .log();
    }

    @EventListener
    public void stopping(ContextClosedEvent event) {
        LoggerFactory.getLogger(getClass())
            .atInfo()
            .setMessage("application stopping")
            .addKeyValue("event", "app.stopping")
            .log();
    }
}
