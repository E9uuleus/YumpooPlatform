package com.yumpoo.platform.testing;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

public final class MigrationAssertions {
    private MigrationAssertions() { }

    public static List<String> migrationVersions() throws IOException {
        var resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/**/V*__*.sql");
        var versions = Arrays.stream(resources)
                .map(resource -> resource.getFilename().split("__", 2)[0].substring(1))
                .sorted((left, right) -> MigrationVersion.fromVersion(left).compareTo(MigrationVersion.fromVersion(right)))
                .toList();
        assertThat(versions).isNotEmpty().doesNotHaveDuplicates();
        return versions;
    }

    public static MigrateResult assertMigrateToLatest(Flyway flyway) {
        var pending = flyway.info().pending();
        assertThat(pending).as("upgrade fixture must have pending forward migrations").isNotEmpty();
        var expected = Arrays.stream(pending).map(migration -> migration.getVersion().getVersion()).toList();
        var result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(expected.size());
        assertThat(result.migrations).extracting(migration -> migration.version).containsExactlyElementsOf(expected);
        assertThat(result.targetSchemaVersion).isEqualTo(expected.getLast());
        assertThat(flyway.info().pending()).isEmpty();
        var validation = flyway.validateWithResult();
        assertThat(validation.validationSuccessful).isTrue();
        assertThat(validation.invalidMigrations).isEmpty();
        return result;
    }
}
