package com.yumpoo.platform.workitem.api.discussion;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkItemUpdateCreateRequest(@NotNull String bodyHtml, UUID parentUpdateId) {}
