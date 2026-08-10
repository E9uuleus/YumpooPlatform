package com.yumpoo.platform.foundation.application.event;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 事件触发主体。System Log 不直接输出这里的原始标识。
 */
public record EventActor(
        EventActorType type,
        UUID userId,
        String systemCode,
        String reasonReference
) {

    private static final Pattern SYSTEM_CODE = Pattern.compile("^[A-Z][A-Z0-9_.:-]{0,79}$");

    public EventActor {
        if (type == null) {
            throw new IllegalArgumentException("actor type must not be null");
        }
        switch (type) {
            case USER -> {
                require(userId != null, "USER actor requires userId");
                require(systemCode == null && reasonReference == null, "USER actor has unexpected fields");
            }
            case SYSTEM -> {
                require(userId == null && reasonReference == null, "SYSTEM actor has unexpected fields");
                require(systemCode != null && SYSTEM_CODE.matcher(systemCode).matches(),
                        "SYSTEM actor requires a stable systemCode");
            }
            case ADMIN_OVERRIDE -> {
                require(userId != null, "ADMIN_OVERRIDE actor requires userId");
                require(systemCode == null, "ADMIN_OVERRIDE actor has unexpected systemCode");
                require(reasonReference != null
                                && !reasonReference.isBlank()
                                && reasonReference.length() <= 160,
                        "ADMIN_OVERRIDE actor requires reasonReference");
            }
        }
    }

    public static EventActor user(UUID userId) {
        return new EventActor(EventActorType.USER, userId, null, null);
    }

    public static EventActor system(String systemCode) {
        return new EventActor(EventActorType.SYSTEM, null, systemCode, null);
    }

    public static EventActor adminOverride(UUID userId, String reasonReference) {
        return new EventActor(EventActorType.ADMIN_OVERRIDE, userId, null, reasonReference);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
