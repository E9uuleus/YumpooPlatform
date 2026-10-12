package com.yumpoo.platform.audit.api;

import java.util.UUID;

public record CompanyActivityItemView(UUID projectId, ActivityItemView activity) {
}
