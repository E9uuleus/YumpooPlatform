package com.yumpoo.platform.workitem.application;

import java.util.UUID;

public record InitializedContentView(
        UUID contentId,
        String code
) {
}
