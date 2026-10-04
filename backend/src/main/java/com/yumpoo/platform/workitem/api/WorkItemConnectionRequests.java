package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class WorkItemConnectionRequests {
    private WorkItemConnectionRequests() {}
    public record Link(@NotNull UUID columnId, @NotNull UUID targetWorkItemId) {}
    public record CreateConnected(@NotNull UUID columnId, @NotNull UUID targetProjectId,
            @NotBlank @Size(max = 300) String title, UUID contentId) {}
    public record CreateReverseConnected(@NotNull UUID columnId, @NotBlank @Size(max = 300) String title, UUID contentId) {}
}
