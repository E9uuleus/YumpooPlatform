package com.yumpoo.platform.testing;

import com.atlassian.oai.validator.OpenApiInteractionValidator;

import java.nio.file.Files;
import java.nio.file.Path;

public final class OpenApiContract {

    private OpenApiContract() {
    }

    static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(specification().toUri().toString())
            .withBasePathOverride("/api/v1")
            .build();

    public static Path specification() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path contracts = Files.isDirectory(workingDirectory.resolve("contracts"))
                ? workingDirectory.resolve("contracts")
                : workingDirectory.resolve("..").resolve("contracts").normalize();
        return contracts.resolve("openapi/yumpoo-v1.yaml");
    }
}
