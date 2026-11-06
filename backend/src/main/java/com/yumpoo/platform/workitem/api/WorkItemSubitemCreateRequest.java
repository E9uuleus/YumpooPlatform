package com.yumpoo.platform.workitem.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record WorkItemSubitemCreateRequest(
        @NotNull UUID contentId,
        @NotBlank @Size(max = 300) String title,
        String priority,
        UUID assigneeUserId,
        @Size(max = 65536) String description,
        @Size(max = 16384) String notes,
        LocalDate timelineStartDate,
        LocalDate timelineEndDate,
        LocalDate dueDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode dueTime,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<UUID> assigneeUserIds) {}
