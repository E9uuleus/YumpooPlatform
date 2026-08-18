package com.yumpoo.platform.identityaccess.infrastructure.bootstrap;

import com.yumpoo.platform.foundation.infrastructure.deployment.DeploymentProperties;
import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitialIdentityBootstrapInputReaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void readsStrictInputWithoutExposingIdentityValues() throws IOException {
        Path secrets = Files.createDirectory(tempDirectory.resolve("secrets"));
        Path input = secrets.resolve("initial-identity-bootstrap.json");
        Files.writeString(input, """
                {
                  "schemaVersion": 1,
                  "confirmation": "M1-15_INITIAL_IDENTITY_BOOTSTRAP",
                  "expectedCorpId": "ww-test-corp",
                  "appManagerWeComUserId": "m115-app-manager",
                  "companyAdminWeComUserId": "m115-company-admin"
                }
                """, StandardCharsets.UTF_8);

        var result = reader(secrets).read(input.toAbsolutePath());

        assertThat(result.expectedCorpId()).isEqualTo("ww-test-corp");
        assertThat(result.appManagerWeComUserId()).isEqualTo("m115-app-manager");
        assertThat(result.companyAdminWeComUserId()).isEqualTo("m115-company-admin");
        assertThat(result.toString()).isEqualTo(
                "InitialIdentityBootstrapInput[identityData=REDACTED]");
    }

    @Test
    void rejectsUnknownFieldsWrongConfirmationAndPathsOutsideSecrets() throws IOException {
        Path secrets = Files.createDirectory(tempDirectory.resolve("secrets"));
        Path unknown = secrets.resolve("unknown.json");
        Files.writeString(unknown, """
                {
                  "schemaVersion": 1,
                  "confirmation": "M1-15_INITIAL_IDENTITY_BOOTSTRAP",
                  "expectedCorpId": "ww-test-corp",
                  "appManagerWeComUserId": "manager",
                  "companyAdminWeComUserId": "admin",
                  "unexpected": true
                }
                """);
        assertCode(reader(secrets), unknown, "INITIAL_IDENTITY_BOOTSTRAP_INPUT_INVALID");

        Path wrong = secrets.resolve("wrong.json");
        Files.writeString(wrong, """
                {
                  "schemaVersion": 1,
                  "confirmation": "WRONG",
                  "expectedCorpId": "ww-test-corp",
                  "appManagerWeComUserId": "manager",
                  "companyAdminWeComUserId": "admin"
                }
                """);
        assertCode(reader(secrets), wrong, "INITIAL_IDENTITY_BOOTSTRAP_INPUT_INVALID");

        Path outside = tempDirectory.resolve("outside.json");
        Files.writeString(outside, "{}");
        assertCode(reader(secrets), outside, "INITIAL_IDENTITY_BOOTSTRAP_INPUT_PATH_INVALID");
    }

    @Test
    void rejectsOversizedAndDuplicateTargets() throws IOException {
        Path secrets = Files.createDirectory(tempDirectory.resolve("secrets"));
        Path oversized = secrets.resolve("oversized.json");
        Files.writeString(oversized, "x".repeat((int) InitialIdentityBootstrapInputReader.MAX_INPUT_BYTES + 1));
        assertCode(reader(secrets), oversized, "INITIAL_IDENTITY_BOOTSTRAP_INPUT_SIZE_INVALID");

        Path duplicate = secrets.resolve("duplicate.json");
        Files.writeString(duplicate, """
                {
                  "schemaVersion": 1,
                  "confirmation": "M1-15_INITIAL_IDENTITY_BOOTSTRAP",
                  "expectedCorpId": "ww-test-corp",
                  "appManagerWeComUserId": "same-member",
                  "companyAdminWeComUserId": "same-member"
                }
                """);
        assertCode(reader(secrets), duplicate,
                "INITIAL_IDENTITY_BOOTSTRAP_TARGETS_NOT_DISTINCT");
    }

    private InitialIdentityBootstrapInputReader reader(Path secrets) {
        DeploymentProperties properties = new DeploymentProperties();
        properties.setSecretsRoot(secrets.toAbsolutePath().toString());
        return new InitialIdentityBootstrapInputReader(new ObjectMapper(), properties);
    }

    private static void assertCode(
            InitialIdentityBootstrapInputReader reader,
            Path input,
            String expectedCode
    ) {
        assertThatThrownBy(() -> reader.read(input.toAbsolutePath()))
                .isInstanceOfSatisfying(
                        InitialIdentityBootstrapException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expectedCode)
                );
    }
}
