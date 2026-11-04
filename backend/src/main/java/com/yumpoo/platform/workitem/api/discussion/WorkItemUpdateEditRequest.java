package com.yumpoo.platform.workitem.api.discussion;

import jakarta.validation.constraints.NotNull;

public record WorkItemUpdateEditRequest(@NotNull String bodyHtml) {}
