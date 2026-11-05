package com.yumpoo.platform.testing;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.parser.util.DeserializationUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OpenApiContract {

    private OpenApiContract() {
    }

    static final OpenApiInteractionValidator VALIDATOR = createValidator();

    private static OpenApiInteractionValidator createValidator() {
        try {
            // The bundled specification uses YAML anchors. Swagger Parser expands aliases before building the tree.
            ObjectNode root = (ObjectNode) DeserializationUtils.readYamlTree(Files.readString(specification()));
            OpenApiNullableReferences.normalize(root);
            return OpenApiInteractionValidator.createForInlineApiSpecification(Yaml.mapper().writeValueAsString(root))
                    .withBasePathOverride("/api/v1")
                    .build();
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取或序列化 OpenAPI 契约", exception);
        }
    }

    public static Path specification() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path contracts = Files.isDirectory(workingDirectory.resolve("contracts"))
                ? workingDirectory.resolve("contracts")
                : workingDirectory.resolve("..").resolve("contracts").normalize();
        return contracts.resolve("openapi/yumpoo-v1.yaml");
    }
}
