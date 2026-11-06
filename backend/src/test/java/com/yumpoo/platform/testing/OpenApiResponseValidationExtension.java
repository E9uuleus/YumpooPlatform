package com.yumpoo.platform.testing;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

class OpenApiResponseValidationExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        OpenApiResponseRecorder.clear();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        List<String> violations = OpenApiResponseRecorder.drain();
        if (!violations.isEmpty()) {
            fail(String.join("\n", violations));
        }
    }
}
