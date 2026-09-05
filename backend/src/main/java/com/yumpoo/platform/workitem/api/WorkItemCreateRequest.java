package com.yumpoo.platform.workitem.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record WorkItemCreateRequest(
        @NotNull UUID contentId,
        @JsonProperty(required = true) @NotBlank @Size(max = 300) String title,
        @JsonProperty(required = true)
        @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT") String priority,
        UUID assigneeUserId,
        @JsonProperty(required = true) @Size(max = 16384) String description,
        @JsonProperty(required = true) @Size(max = 16384) String notes,
        LocalDate timelineStartDate,
        LocalDate timelineEndDate,
        LocalDate dueDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode dueTime
) {}
