package com.yumpoo.platform.foundation.infrastructure.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentPreflightTest {

    @TempDir
    Path tempDirectory;

    private DeploymentProperties properties;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() throws IOException {
        properties = new DeploymentProperties();
        properties.setPublicBaseUrl("https://yumpoo.example.test");
        properties.setReleaseRoot(directory("release").toString());
        properties.setConfigRoot(directory("config").toString());
        properties.setSecretsRoot(directory("secrets").toString());
        properties.setAttachmentRoot(directory("attachments").toString());
        properties.setUploadTempRoot(directory("uploads").toString());
        properties.setLogRoot(directory("logs").toString());

        environment = new MockEnvironment()
                .withProperty("server.address", "127.0.0.1")
                .withProperty("server.port", "18080")
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/yumpoo")
                .withProperty("spring.datasource.username", "yumpoo_app")
                .withProperty("spring.datasource.password", "A1!yumpoo-app-2026")
                .withProperty("spring.flyway.url", "jdbc:postgresql://127.0.0.1:5432/yumpoo")
                .withProperty("spring.flyway.user", "yumpoo_migrator")
                .withProperty("spring.flyway.password", "B2!yumpoo-ddl-2026")
                .withProperty("yumpoo.session.current-key-version", "prod-v1")
                .withProperty(
                        "yumpoo.session.current-key",
                        "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
                );
    }

    @Test
    void validProductionConfigurationProducesWritableRuntimePaths() {
        DeploymentPaths paths = new DeploymentPreflight(properties, environment).validate();

        DeploymentDirectoryProbe probe = new DeploymentDirectoryProbe(paths);

        assertThat(probe.health().getStatus()).isEqualTo(Status.UP);
        assertThat(paths.writableRuntimeRoots()).hasSize(3);
    }

    @Test
    void runtimeDirectoryFailureIsReportedWithoutDetailsAndCanRecover() throws IOException {
        DeploymentPaths paths = new DeploymentPreflight(properties, environment).validate();
        DeploymentDirectoryProbe probe = new DeploymentDirectoryProbe(paths);
        Path attachmentRoot = paths.attachmentRoot();

        Files.delete(attachmentRoot);

        assertThat(probe.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(probe.health().getDetails()).isEmpty();

        Files.createDirectory(attachmentRoot);

        assertThat(probe.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void publicAddressMustBeAnHttpsOrigin() {
        properties.setPublicBaseUrl("http://yumpoo.example.test/path?leak=value");

        assertFailure("PUBLIC_ORIGIN_INVALID", "yumpoo.deployment.public-base-url");
    }

    @Test
    void bindAddressMustRemainExactLoopback() {
        environment.setProperty("server.address", "0.0.0.0");

        assertFailure("BIND_ADDRESS_INVALID", "server.address");
    }

    @Test
    void privilegedOrInvalidPortIsRejected() {
        environment.setProperty("server.port", "443");

        assertFailure("BIND_ADDRESS_INVALID", "server.port");
    }

    @Test
    void applicationAndMigrationConnectionsMustTargetTheSameLoopbackDatabase() {
        environment.setProperty("spring.flyway.url", "jdbc:postgresql://127.0.0.1:5432/another");

        assertFailure("DATABASE_INVALID", "spring.flyway.url");
    }

    @Test
    void databaseCredentialsMustBeSeparateAndNonPlaceholder() {
        environment.setProperty("spring.flyway.password", "change-me-password");

        assertFailure("SECRET_INVALID", "spring.flyway.password");
    }

    @Test
    void databaseUsersMustBeSeparate() {
        environment.setProperty("spring.flyway.user", "yumpoo_app");

        assertFailure("DATABASE_INVALID", "spring.flyway.user");
    }

    @Test
    void shortSecretIsRejectedAfterWhitespaceIsTrimmed() {
        environment.setProperty("spring.datasource.password", "   Short-1!   ");

        assertFailure("SECRET_INVALID", "spring.datasource.password");
    }

    @Test
    void equalApplicationAndMigrationSecretsAreRejected() {
        environment.setProperty("spring.flyway.password", "A1!yumpoo-app-2026");

        assertFailure("SECRET_INVALID", "spring.flyway.password");
    }

    @Test
    void nonLoopbackDatabaseIsRejected() {
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://192.0.2.10:5432/yumpoo");

        assertFailure("DATABASE_INVALID", "spring.datasource.url");
    }

    @Test
    void relativeAndMissingDirectoriesAreRejected() {
        properties.setLogRoot("relative/logs");
        assertFailure("PATH_INVALID", "yumpoo.deployment.log-root");

        properties.setLogRoot(tempDirectory.resolve("missing").toString());
        assertFailure("PATH_INVALID", "yumpoo.deployment.log-root");
    }

    @Test
    void rootsMustNotOverlap() throws IOException {
        Path releaseRoot = Path.of(properties.getReleaseRoot());
        Path nested = Files.createDirectory(releaseRoot.resolve("attachments"));
        properties.setAttachmentRoot(nested.toString());

        assertFailure("PATH_INVALID", "yumpoo.deployment.*-root");
    }

    private void assertFailure(String code, String propertyName) {
        assertThatThrownBy(() -> new DeploymentPreflight(properties, environment).validate())
                .isInstanceOf(DeploymentValidationException.class)
                .hasMessage(code + ":" + propertyName)
                .extracting(exception -> ((DeploymentValidationException) exception).code())
                .isEqualTo(code);
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectory(tempDirectory.resolve(name));
    }
}
