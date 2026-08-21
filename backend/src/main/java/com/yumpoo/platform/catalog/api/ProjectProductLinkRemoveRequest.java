package com.yumpoo.platform.catalog.api;

import jakarta.validation.constraints.Size;

public record ProjectProductLinkRemoveRequest(
        @Size(max = 500) String reason
) {
}
