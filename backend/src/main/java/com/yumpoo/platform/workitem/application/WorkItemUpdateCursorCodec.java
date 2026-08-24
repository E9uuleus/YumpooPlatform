package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateCursor;

final class WorkItemUpdateCursorCodec {
    private static final int MAX_CURSOR_LENGTH = 256;

    String encode(UpdateCursor cursor) {
        String value = "v1\n" + cursor.createdAt() + "\n" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    UpdateCursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() > MAX_CURSOR_LENGTH) throw new IllegalArgumentException();
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\n", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            return new UpdateCursor(Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (RuntimeException exception) {
            throw ApplicationException.validation(new FieldViolation(
                    "cursor", "INVALID_CURSOR", "讨论游标无效"));
        }
    }
}
