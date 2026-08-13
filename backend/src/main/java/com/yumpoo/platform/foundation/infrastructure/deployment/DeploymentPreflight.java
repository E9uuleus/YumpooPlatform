package com.yumpoo.platform.foundation.infrastructure.deployment;

import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DeploymentPreflight {

    private static final String JAVA_VERSION_INVALID = "YUMPOO_CONFIG_JAVA_VERSION_INVALID";
    private static final String PUBLIC_ORIGIN_INVALID = "PUBLIC_ORIGIN_INVALID";
    private static final String BIND_ADDRESS_INVALID = "BIND_ADDRESS_INVALID";
    private static final String DATABASE_INVALID = "DATABASE_INVALID";
    private static final String PATH_INVALID = "PATH_INVALID";
    private static final String SECRET_INVALID = "SECRET_INVALID";
    private static final int MINIMUM_SECRET_CODE_POINTS = 16;
    private static final Set<String> INSECURE_SECRET_MARKERS = Set.of(
            "change-me",
            "changeme",
            "placeholder",
            "password",
            "secret-key"
    );

    private final DeploymentProperties properties;
    private final Environment environment;

    DeploymentPreflight(DeploymentProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    DeploymentPaths validate() {
        validateJavaVersion();
        validateBindAddress();
        validatePublicOrigin();
        validateDatabase();
        validateSessionSecurity();
        return validatePaths();
    }

    private void validateJavaVersion() {
        if (!"21".equals(System.getProperty("java.specification.version"))) {
            fail(JAVA_VERSION_INVALID, "java.specification.version");
        }
    }

    private void validateBindAddress() {
        if (!"127.0.0.1".equals(requiredEnvironment("server.address", BIND_ADDRESS_INVALID))) {
            fail(BIND_ADDRESS_INVALID, "server.address");
        }
        String rawPort = requiredEnvironment("server.port", BIND_ADDRESS_INVALID);
        try {
            int port = Integer.parseInt(rawPort);
            if (port < 1024 || port > 65535) {
                fail(BIND_ADDRESS_INVALID, "server.port");
            }
        } catch (NumberFormatException exception) {
            fail(BIND_ADDRESS_INVALID, "server.port");
        }
    }

    private void validatePublicOrigin() {
        String raw = required(properties.getPublicBaseUrl(), "yumpoo.deployment.public-base-url", PUBLIC_ORIGIN_INVALID);
        try {
            URI uri = new URI(raw);
            String path = uri.getRawPath();
            if (!uri.isAbsolute()
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !(path == null || path.isEmpty() || "/".equals(path))) {
                fail(PUBLIC_ORIGIN_INVALID, "yumpoo.deployment.public-base-url");
            }
        } catch (URISyntaxException exception) {
            fail(PUBLIC_ORIGIN_INVALID, "yumpoo.deployment.public-base-url");
        }
    }

    private void validateDatabase() {
        JdbcTarget application = jdbcTarget(
                requiredEnvironment("spring.datasource.url", DATABASE_INVALID),
                "spring.datasource.url"
        );
        JdbcTarget migration = jdbcTarget(
                requiredEnvironment("spring.flyway.url", DATABASE_INVALID),
                "spring.flyway.url"
        );
        if (!application.equals(migration)) {
            fail(DATABASE_INVALID, "spring.flyway.url");
        }

        String applicationUser = requiredEnvironment("spring.datasource.username", DATABASE_INVALID);
        String migrationUser = requiredEnvironment("spring.flyway.user", DATABASE_INVALID);
        if (applicationUser.equals(migrationUser)) {
            fail(DATABASE_INVALID, "spring.flyway.user");
        }

        String applicationPassword = validateSecret("spring.datasource.password");
        String migrationPassword = validateSecret("spring.flyway.password");
        if (applicationPassword.equals(migrationPassword)) {
            fail(SECRET_INVALID, "spring.flyway.password");
        }
    }

    private String validateSecret(String propertyName) {
        String secret = requiredEnvironment(propertyName, SECRET_INVALID).trim();
        String normalized = secret.toLowerCase(Locale.ROOT);
        if (secret.codePointCount(0, secret.length()) < MINIMUM_SECRET_CODE_POINTS
                || INSECURE_SECRET_MARKERS.stream().anyMatch(normalized::contains)) {
            fail(SECRET_INVALID, propertyName);
        }
        return secret;
    }

    private void validateSessionSecurity() {
        String currentVersion = requiredEnvironment(
                "yumpoo.session.current-key-version",
                SECRET_INVALID
        );
        if (!currentVersion.matches("[A-Za-z0-9._-]{1,32}")) {
            fail(SECRET_INVALID, "yumpoo.session.current-key-version");
        }
        validateBase64Secret("yumpoo.session.current-key");

        String previousVersion = optionalEnvironment("yumpoo.session.previous-key-version");
        String previousKey = optionalEnvironment("yumpoo.session.previous-key");
        String previousUntil = optionalEnvironment("yumpoo.session.previous-accept-until");
        boolean any = !previousVersion.isBlank() || !previousKey.isBlank() || !previousUntil.isBlank();
        if (!any) {
            return;
        }
        if (!previousVersion.matches("[A-Za-z0-9._-]{1,32}")
                || previousVersion.equals(currentVersion)
                || previousKey.isBlank()
                || previousUntil.isBlank()) {
            fail(SECRET_INVALID, "yumpoo.session.previous-*");
        }
        validateBase64Secret("yumpoo.session.previous-key");
        try {
            Instant.parse(previousUntil);
        } catch (DateTimeParseException exception) {
            fail(SECRET_INVALID, "yumpoo.session.previous-accept-until");
        }
    }

    private void validateBase64Secret(String propertyName) {
        String encoded = requiredEnvironment(propertyName, SECRET_INVALID);
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length < 32) {
                fail(SECRET_INVALID, propertyName);
            }
        } catch (IllegalArgumentException exception) {
            fail(SECRET_INVALID, propertyName);
        }
    }

    private String optionalEnvironment(String propertyName) {
        String value = environment.getProperty(propertyName);
        return value == null ? "" : value.trim();
    }

    private DeploymentPaths validatePaths() {
        DeploymentPaths paths = new DeploymentPaths(
                readableRoot(properties.getReleaseRoot(), "yumpoo.deployment.release-root"),
                readableRoot(properties.getConfigRoot(), "yumpoo.deployment.config-root"),
                readableRoot(properties.getSecretsRoot(), "yumpoo.deployment.secrets-root"),
                writableRoot(properties.getAttachmentRoot(), "yumpoo.deployment.attachment-root"),
                writableRoot(properties.getUploadTempRoot(), "yumpoo.deployment.upload-temp-root"),
                writableRoot(properties.getLogRoot(), "yumpoo.deployment.log-root")
        );
        List<Path> all = List.of(
                paths.releaseRoot(),
                paths.configRoot(),
                paths.secretsRoot(),
                paths.attachmentRoot(),
                paths.uploadTempRoot(),
                paths.logRoot()
        );
        if (new HashSet<>(all).size() != all.size()) {
            fail(PATH_INVALID, "yumpoo.deployment.*-root");
        }
        for (int left = 0; left < all.size(); left++) {
            for (int right = left + 1; right < all.size(); right++) {
                Path first = all.get(left);
                Path second = all.get(right);
                if (first.startsWith(second) || second.startsWith(first)) {
                    fail(PATH_INVALID, "yumpoo.deployment.*-root");
                }
            }
        }
        try {
            FileStore attachmentStore = Files.getFileStore(paths.attachmentRoot());
            FileStore uploadStore = Files.getFileStore(paths.uploadTempRoot());
            if (!attachmentStore.equals(uploadStore)) {
                fail(PATH_INVALID, "yumpoo.deployment.upload-temp-root");
            }
        } catch (IOException exception) {
            fail(PATH_INVALID, "yumpoo.deployment.upload-temp-root");
        }
        return paths;
    }

    private Path readableRoot(String raw, String propertyName) {
        Path path = realDirectory(raw, propertyName);
        if (!Files.isReadable(path)) {
            fail(PATH_INVALID, propertyName);
        }
        return path;
    }

    private Path writableRoot(String raw, String propertyName) {
        Path path = realDirectory(raw, propertyName);
        if (!DeploymentDirectoryProbe.canWrite(path)) {
            fail(PATH_INVALID, propertyName);
        }
        return path;
    }

    private Path realDirectory(String raw, String propertyName) {
        String value = required(raw, propertyName, PATH_INVALID);
        try {
            Path configured = Path.of(value);
            if (!configured.isAbsolute()) {
                fail(PATH_INVALID, propertyName);
            }
            Path real = configured.toRealPath();
            if (!Files.isDirectory(real)) {
                fail(PATH_INVALID, propertyName);
            }
            return real;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof DeploymentValidationException validationException) {
                throw validationException;
            }
            fail(PATH_INVALID, propertyName);
            throw new IllegalStateException("unreachable");
        }
    }

    private JdbcTarget jdbcTarget(String value, String propertyName) {
        if (!value.startsWith("jdbc:postgresql:")) {
            fail(DATABASE_INVALID, propertyName);
        }
        try {
            URI uri = new URI(value.substring("jdbc:".length()));
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String path = uri.getPath();
            if (!"postgresql".equals(uri.getScheme())
                    || !"127.0.0.1".equals(uri.getHost())
                    || port < 1
                    || port > 65535
                    || path == null
                    || path.length() <= 1
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                fail(DATABASE_INVALID, propertyName);
            }
            return new JdbcTarget(uri.getHost(), port, path.substring(1));
        } catch (URISyntaxException exception) {
            fail(DATABASE_INVALID, propertyName);
            throw new IllegalStateException("unreachable");
        }
    }

    private String requiredEnvironment(String propertyName, String errorCode) {
        return required(environment.getProperty(propertyName), propertyName, errorCode);
    }

    private String required(String value, String propertyName, String errorCode) {
        if (value == null || value.isBlank()) {
            fail(errorCode, propertyName);
        }
        return value.trim();
    }

    private static void fail(String code, String propertyName) {
        throw new DeploymentValidationException(code, propertyName);
    }

    private record JdbcTarget(String host, int port, String database) {
    }
}
