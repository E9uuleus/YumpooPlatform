package com.yumpoo.platform.audit.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
class ActivityCursorCodec {
    record Cursor(String fingerprint, ActivityRepository.CursorAnchor anchor) {
    }

    String encode(Cursor cursor) {
        String value = "v1\n" + cursor.fingerprint() + "\n" + cursor.anchor().occurredAt()
                + "\n" + cursor.anchor().id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    Cursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value),
                    StandardCharsets.UTF_8).split("\\n", -1);
            if (parts.length != 4 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            return new Cursor(parts[1], new ActivityRepository.CursorAnchor(
                    Instant.parse(parts[2]), UUID.fromString(parts[3])));
        } catch (RuntimeException failure) {
            throw invalidCursor();
        }
    }

    static ApplicationException invalidCursor() {
        return new ApplicationException(StandardErrorCode.VALIDATION_FAILED,
                StandardErrorCode.VALIDATION_FAILED.defaultMessage(),
                java.util.List.of(new FieldViolation("cursor", "INVALID_CURSOR", "游标无效或与筛选条件不匹配")));
    }
}
