package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record ConnectColumnRequest(@NotBlank @Size(max = 40) String name,
        @NotNull @Size(min = 1, max = 20) List<@NotNull UUID> targetProjectIds) {}
