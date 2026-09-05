package com.yumpoo.platform.workitem.api;

import java.util.UUID;

public record InitializedProjectContent(
        UUID contentId,
        String code
) {
}
