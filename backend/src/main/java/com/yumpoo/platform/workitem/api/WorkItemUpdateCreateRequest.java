package com.yumpoo.platform.workitem.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record WorkItemUpdateCreateRequest(@NotNull String bodyHtml, UUID parentUpdateId) {}
