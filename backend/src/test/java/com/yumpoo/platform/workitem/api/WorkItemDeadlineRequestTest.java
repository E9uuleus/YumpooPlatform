package com.yumpoo.platform.workitem.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WorkItemDeadlineRequestTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void omissionAndExplicitNullRemainDifferentInDtoAndIdempotencyInput() {
        var omitted = json.readValue("{\"dueDate\":\"2026-09-03\"}", WorkItemDueDatePatchRequest.class);
        var cleared = json.readValue("{\"dueDate\":\"2026-09-03\",\"dueTime\":null}", WorkItemDueDatePatchRequest.class);
        var timed = json.readValue("{\"dueDate\":\"2026-09-03\",\"dueTime\":\"18:05\"}", WorkItemDueDatePatchRequest.class);
        assertThat(omitted.dueTime()).isNull();
        assertThat(cleared.dueTime().isNull()).isTrue();
        assertThat(timed.dueTime().asText()).isEqualTo("18:05");
        assertThat(json.valueToTree(omitted).has("dueTime")).isFalse();
        assertThat(json.valueToTree(cleared).has("dueTime")).isTrue();
        assertThat(json.writeValueAsString(omitted)).isNotEqualTo(json.writeValueAsString(cleared));
    }
}
