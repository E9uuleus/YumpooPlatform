package com.yumpoo.platform.identityaccess.infrastructure.bootstrap;

import com.yumpoo.platform.foundation.infrastructure.deployment.DeploymentProperties;
import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapException;
import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapInput;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.Locale;
import java.util.Set;

@Component
@ConditionalOnProperty(
        prefix = "yumpoo.maintenance.initial-identity",
        name = "enabled",
        havingValue = "true"
)
public class InitialIdentityBootstrapInputReader {

    static final long MAX_INPUT_BYTES = 8L * 1024L;
    static final String CONFIRMATION = "M1-15_INITIAL_IDENTITY_BOOTSTRAP";
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "confirmation",
            "expectedCorpId",
            "appManagerWeComUserId",
            "companyAdminWeComUserId"
    );

    private final ObjectMapper objectMapper;
    private final DeploymentProperties deploymentProperties;

    public InitialIdentityBootstrapInputReader(
            ObjectMapper objectMapper,
            DeploymentProperties deploymentProperties
    ) {
        this.objectMapper = objectMapper;
        this.deploymentProperties = deploymentProperties;
    }

    public InitialIdentityBootstrapInput read(Path configuredPath) {
        Path input = requireSafePath(configuredPath);
        try {
            long size = Files.size(input);
            if (size == 0 || size > MAX_INPUT_BYTES) {
                throw invalid("INITIAL_IDENTITY_BOOTSTRAP_INPUT_SIZE_INVALID");
            }
            String json = Files.readString(input, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw invalid("INITIAL_IDENTITY_BOOTSTRAP_INPUT_INVALID");
            }
            Set<String> actualFields = root.properties().stream()
                    .map(java.util.Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!actualFields.equals(FIELDS)
                    || root.path("schemaVersion").asInt(-1) != 1
                    || !CONFIRMATION.equals(text(root, "confirmation"))) {
                throw invalid("INITIAL_IDENTITY_BOOTSTRAP_INPUT_INVALID");
            }
            return new InitialIdentityBootstrapInput(
                    text(root, "expectedCorpId"),
                    text(root, "appManagerWeComUserId"),
                    text(root, "companyAdminWeComUserId")
            );
        } catch (InitialIdentityBootstrapException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw invalid("INITIAL_IDENTITY_BOOTSTRAP_INPUT_INVALID");
        } catch (IOException exception) {
            throw invalid("INITIAL_IDENTITY_BOOTSTRAP_INPUT_UNREADABLE");
        }
    }

    private Path requireSafePath(Path configuredPath) {
        if (configuredPath == null || !configuredPath.isAbsolute()) {
            throw invalid("INITIAL_IDENTITY_BOOTSTRAP_INPUT_PATH_INVALID");
        }
        try {
            Path secretsRoot = Path.of(deploymentProperties.getSecretsRoot()).toRealPath();
            Path normalized = configuredPath.normalize();
            if (Files.isSymbolicLink(normalized)
                    || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("INITIAL_IDENTITY_BOOTSTRAP_INPUT_PATH_INVALID");
            }
            Path realInput = normalized.toRealPath();
            if (!realInput.startsWith(secretsRoot)) {
                throw invalid("INITIAL_IDENTITY_BOOTSTRAP_INPUT_PATH_INVALID");
            }
            rejectBroadReadAcl(realInput);
            return realInput;
        } catch (InitialIdentityBootstrapException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("INITIAL_IDENTITY_BOOTSTRAP_INPUT_PATH_INVALID");
        }
    }

    private static void rejectBroadReadAcl(Path input) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                input, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return;
        }
        for (var entry : view.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW
                    || !entry.permissions().contains(AclEntryPermission.READ_DATA)) {
                continue;
            }
            String principal = entry.principal().getName().toUpperCase(Locale.ROOT);
            if (principal.equals("EVERYONE")
                    || principal.endsWith("\\EVERYONE")
                    || principal.endsWith("\\USERS")
                    || principal.endsWith("\\AUTHENTICATED USERS")
                    || principal.equals("所有人")
                    || principal.endsWith("\\用户")
                    || principal.endsWith("\\经过身份验证的用户")) {
                throw invalid("INITIAL_IDENTITY_BOOTSTRAP_INPUT_ACL_INVALID");
            }
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static InitialIdentityBootstrapException invalid(String code) {
        return new InitialIdentityBootstrapException(
                "INPUT",
                code,
                "Initial identity bootstrap input was rejected"
        );
    }
}
