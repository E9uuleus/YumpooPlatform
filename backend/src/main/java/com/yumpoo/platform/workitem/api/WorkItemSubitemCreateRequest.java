package com.yumpoo.platform.workitem.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record WorkItemSubitemCreateRequest(
        @NotNull UUID contentId,
        @NotBlank @Size(max = 300) String title,
        String priority,
        UUID assigneeUserId,
        @Size(max = 16384) String description,
        @Size(max = 16384) String notes,
        LocalDate timelineStartDate,
        LocalDate timelineEndDate,
        LocalDate dueDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode dueTime
) {}
