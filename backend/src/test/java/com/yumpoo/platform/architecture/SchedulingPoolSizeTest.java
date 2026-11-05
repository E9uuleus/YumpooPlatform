package com.yumpoo.platform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;

class SchedulingPoolSizeTest {

    @Test
    void everyScheduledPollerHasItsOwnSchedulerThread() throws IOException {
        long pollers = new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.yumpoo.platform")
                        .stream()
                        .flatMap(type -> type.getMethods().stream())
                        .filter(method -> method.isAnnotatedWith(Scheduled.class))
                        .count();
        Object poolSize = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst()
                .getProperty("spring.task.scheduling.pool.size");

        assertThat(Long.parseLong(String.valueOf(poolSize))).isEqualTo(pollers);
    }
}
