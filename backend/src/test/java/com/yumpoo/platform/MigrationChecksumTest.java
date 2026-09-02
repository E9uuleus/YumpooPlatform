package com.yumpoo.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.internal.resource.classpath.ClassPathResource;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.junit.jupiter.api.Test;

class MigrationChecksumTest {

    @Test
    void appliedV46ChecksumStaysFrozen() {
        var migration = new ClassPathResource(
                new Location("classpath:db/migration"),
                "db/migration/audit/V46__create_work_item_cell_activity_projection.sql",
                getClass().getClassLoader(),
                StandardCharsets.UTF_8);

        assertThat(ChecksumCalculator.calculate(migration)).isEqualTo(1108579806);
    }
}
