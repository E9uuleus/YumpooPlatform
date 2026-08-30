package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class ProjectWorkItemFilterCursorCodec {
    record Cursor(String fingerprint, String field, String lastValue) {}

    String encode(Cursor cursor) {
        String last = Base64.getUrlEncoder().withoutPadding().encodeToString(
                cursor.lastValue().getBytes(StandardCharsets.UTF_8));
        String payload = "v1\n" + cursor.fingerprint() + "\n" + cursor.field() + "\n" + last;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
    }

    Cursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() > 2048) throw new IllegalArgumentException();
            String payload = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\n", -1);
            if (parts.length != 4 || !"v1".equals(parts[0]) || parts[1].isBlank())
                throw new IllegalArgumentException();
            String last = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
            return new Cursor(parts[1], parts[2], last);
        } catch (RuntimeException exception) {
            throw ApplicationException.validation(new FieldViolation(
                    "cursor", "INVALID_CURSOR", "筛选选项分页游标无效"));
        }
    }
}
