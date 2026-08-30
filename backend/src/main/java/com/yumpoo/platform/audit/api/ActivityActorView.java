package com.yumpoo.platform.audit.api;

import java.util.UUID;

public record ActivityActorView(String type, UUID userId, String displayName) {
}
