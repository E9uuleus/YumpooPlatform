package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.workitem.domain.WorkItemViewType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemRepository.ProjectCursorAnchor;

final class ProjectWorkItemCursorCodec {
    private static final int MAX_CURSOR_LENGTH = 2048;

    record Cursor(String fingerprint, WorkItemViewType view, ProjectCursorAnchor anchor) {}

    String encode(Cursor cursor) {
        ProjectCursorAnchor anchor = cursor.anchor();
        String value = String.join("\n", "v4", cursor.fingerprint(), cursor.view().name(),
                anchor.id().toString(), anchor.rank(), anchor.projectSortKey(), Long.toString(anchor.itemSequence()),
                anchor.contentId().toString(), text(anchor.title()), text(anchor.statusCode()), nullable(anchor.priority()),
                nullable(anchor.assigneeUserId()), nullable(anchor.reporterUserId()),
                nullable(anchor.timelineStartDate()), nullable(anchor.timelineEndDate()),
                nullable(anchor.dueDate()), anchor.updatedAt().toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    Cursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() > MAX_CURSOR_LENGTH) throw new IllegalArgumentException();
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\n", -1);
            if (parts.length != 17 || !"v4".equals(parts[0]) || parts[1].isBlank())
                throw new IllegalArgumentException();
            ProjectCursorAnchor anchor = new ProjectCursorAnchor(
                    UUID.fromString(parts[3]), parts[4], parts[5], Long.parseLong(parts[6]),
                    UUID.fromString(parts[7]), decodedText(parts[8]), decodedText(parts[9]),
                    priority(parts[10]), uuid(parts[11]), uuid(parts[12]), date(parts[13]),
                    date(parts[14]), date(parts[15]), Instant.parse(parts[16]));
            return new Cursor(parts[1], WorkItemViewType.valueOf(parts[2]), anchor);
        } catch (RuntimeException exception) {
            throw ApplicationException.validation(new FieldViolation(
                    "cursor", "INVALID_CURSOR", "工作项分页游标无效"));
        }
    }

    private static String text(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodedText(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String nullable(Object value) { return value == null ? "-" : value.toString(); }
    private static UUID uuid(String value) { return "-".equals(value) ? null : UUID.fromString(value); }
    private static LocalDate date(String value) {
        return "-".equals(value) ? null : LocalDate.parse(value);
    }
    private static String priority(String value) {
        return "-".equals(value) ? null : value;
    }
}
