package com.yumpoo.platform.workitem.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkItemCreateRequestTest {
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void priorityAcceptsProjectLabelCodesAndRejectsMalformedValues() {
        assertThat(validator.validate(request("PRIORITY_A1B2C3D4E5F6"))).isEmpty();
        assertThat(validator.validate(request("HIGH"))).isEmpty();
        assertThat(validator.validate(request("high"))).isNotEmpty();
    }

    private static WorkItemCreateRequest request(String priority) {
        return new WorkItemCreateRequest(
                UUID.randomUUID(), "标题", priority, null, null, null, null, null, null, null, null);
    }
}
