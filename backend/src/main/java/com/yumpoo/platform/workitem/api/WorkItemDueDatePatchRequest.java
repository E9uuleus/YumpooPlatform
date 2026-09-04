package com.yumpoo.platform.workitem.api;

import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

public record WorkItemDueDatePatchRequest(
        @JsonProperty(required = true) LocalDate dueDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode dueTime) {}
