package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentFileType;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;

import java.util.Locale;
import java.util.Set;

/** 文件名净化、扩展名允许列表和双扩展伪装检查。 */
public final class AttachmentFileNamePolicy {

    private static final int MAX_DISPLAY_NAME_CODE_POINTS = 255;
    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
            "exe", "dll", "msi", "bat", "cmd", "ps1", "js", "jar", "lnk",
            "html", "htm", "svg", "docm", "xlsm", "pptm", "com", "scr", "vbs"
    );

    private AttachmentFileNamePolicy() {
    }

    public static AttachmentFileName normalize(String original) {
        if (original == null || original.isBlank()) {
            throw typeRejected();
        }
        StringBuilder sanitized = new StringBuilder();
        original.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT) {
                return;
            }
            if (codePoint == '/' || codePoint == '\\' || codePoint == ':') {
                sanitized.append('_');
            } else {
                sanitized.appendCodePoint(codePoint);
            }
        });
        String displayName = stripTrailingDotsAndSpaces(sanitized.toString().strip());
        if (displayName.isBlank()) {
            throw typeRejected();
        }
        if (displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_CODE_POINTS) {
            int end = displayName.offsetByCodePoints(0, MAX_DISPLAY_NAME_CODE_POINTS);
            displayName = stripTrailingDotsAndSpaces(displayName.substring(0, end));
        }

        String lower = displayName.toLowerCase(Locale.ROOT);
        String[] segments = lower.split("\\.", -1);
        if (segments.length < 2) {
            throw typeRejected();
        }
        String extension = segments[segments.length - 1];
        String baseName = lower.substring(0, lower.length() - extension.length() - 1)
                .replace(".", "")
                .replace("_", "")
                .strip();
        if (baseName.isBlank()) {
            throw typeRejected();
        }
        AttachmentFileType type = AttachmentFileType.fromExtension(extension)
                .orElseThrow(AttachmentFileNamePolicy::typeRejected);
        for (int index = 1; index < segments.length - 1; index++) {
            if (DANGEROUS_EXTENSIONS.contains(segments[index])) {
                throw typeRejected();
            }
        }
        return new AttachmentFileName(displayName, extension, type);
    }

    private static String stripTrailingDotsAndSpaces(String value) {
        int end = value.length();
        while (end > 0) {
            char current = value.charAt(end - 1);
            if (current != '.' && current != ' ') {
                break;
            }
            end--;
        }
        return value.substring(0, end);
    }

    private static UploadRejectedException typeRejected() {
        return new UploadRejectedException(AttachmentRejectedCode.FILE_TYPE_NOT_ALLOWED);
    }
}
