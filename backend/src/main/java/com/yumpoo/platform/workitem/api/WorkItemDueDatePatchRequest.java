package com.yumpoo.platform.workitem.api;

import java.time.LocalDate;

public record WorkItemDueDatePatchRequest(LocalDate dueDate) {}
