package com.yumpoo.platform.workitem.api.discussion;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record WorkItemUpdateCreateRequest(@NotNull String bodyHtml, UUID parentUpdateId) {}
