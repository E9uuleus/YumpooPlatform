package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.Size;

public record WorkItemUpdateDeleteRequest(@Size(max = 500) String reason) {}
