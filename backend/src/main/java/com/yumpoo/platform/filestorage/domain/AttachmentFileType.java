package com.yumpoo.platform.filestorage.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** M0-14 冻结的默认允许类型及其可接受声明 MIME。 */
public enum AttachmentFileType {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    TXT("txt", "text/plain"),
    CSV("csv", "text/csv", "application/csv", "text/plain"),
    MARKDOWN("md", "text/markdown", "text/plain"),
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    GIF("gif", "image/gif"),
    ZIP("zip", "application/zip", "application/x-zip-compressed");

    private final String extension;
    private final String canonicalMime;
    private final Set<String> declaredMimes;

    AttachmentFileType(String extension, String canonicalMime, String... aliases) {
        this.extension = extension;
        this.canonicalMime = canonicalMime;
        String[] values = new String[aliases.length + 1];
        values[0] = canonicalMime;
        System.arraycopy(aliases, 0, values, 1, aliases.length);
        this.declaredMimes = Set.of(values);
    }

    public String extension() {
        return extension;
    }

    public String canonicalMime() {
        return canonicalMime;
    }

    public boolean acceptsDeclaredMime(String value) {
        if (value == null) {
            return false;
        }
        return declaredMimes.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    public static Optional<AttachmentFileType> fromExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        if ("jpeg".equals(normalized)) {
            return Optional.of(JPEG);
        }
        if ("markdown".equals(normalized)) {
            return Optional.of(MARKDOWN);
        }
        return Arrays.stream(values())
                .filter(type -> type.extension.equals(normalized))
                .findFirst();
    }

    public boolean isText() {
        return this == TXT || this == CSV || this == MARKDOWN;
    }

    public boolean isZipContainer() {
        return this == DOCX || this == XLSX || this == PPTX || this == ZIP;
    }
}
