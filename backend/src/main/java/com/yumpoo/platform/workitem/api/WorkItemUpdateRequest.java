package com.yumpoo.platform.workitem.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record WorkItemUpdateRequest(
        @JsonProperty(required = true) @NotBlank @Size(max = 300) String title,
        @JsonProperty(required = true)
        @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT") String priority,
        @JsonProperty(required = true) UUID assigneeUserId,
        @JsonProperty(required = true) @Size(max = 16384) String description,
        @JsonProperty(required = true) @Size(max = 16384) String notes,
        @JsonProperty(required = true) LocalDate timelineStartDate,
        @JsonProperty(required = true) LocalDate timelineEndDate,
        @JsonProperty(required = true) LocalDate dueDate
) {}
