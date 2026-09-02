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
public class WorkItemCellActivityCursorCodec {
    public record Cursor(String fingerprint, Instant snapshotAt,
            WorkItemCellActivityRepository.CursorAnchor anchor) {}

    public String encode(Cursor cursor) {
        String value = "v1\n" + cursor.fingerprint() + "\n" + cursor.snapshotAt() + "\n"
                + cursor.anchor().occurredAt() + "\n" + cursor.anchor().id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public Cursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value),
                    StandardCharsets.UTF_8).split("\\n", -1);
            if (parts.length != 5 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            return new Cursor(parts[1], Instant.parse(parts[2]),
                    new WorkItemCellActivityRepository.CursorAnchor(Instant.parse(parts[3]),
                            UUID.fromString(parts[4])));
        } catch (RuntimeException failure) {
            throw invalidCursor();
        }
    }

    public static ApplicationException invalidCursor() {
        return new ApplicationException(StandardErrorCode.VALIDATION_FAILED,
                StandardErrorCode.VALIDATION_FAILED.defaultMessage(),
                java.util.List.of(new FieldViolation("cursor", "INVALID_CURSOR",
                        "游标无效或与筛选条件不匹配")));
    }
}
