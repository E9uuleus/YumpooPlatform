package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.AttachmentContentDetector;
import com.yumpoo.platform.filestorage.application.AttachmentFileName;
import com.yumpoo.platform.filestorage.application.AttachmentUploadPolicy;
import com.yumpoo.platform.filestorage.application.DetectedAttachmentContent;
import com.yumpoo.platform.filestorage.application.UploadRejectedException;
import com.yumpoo.platform.filestorage.domain.AttachmentFileType;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Tika 魔数识别 + 有界 ZIP/OOXML 容器判别。 */
public final class TikaAttachmentContentDetector implements AttachmentContentDetector {

    private static final int TEXT_SAMPLE_BYTES = 64 * 1024;
    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final int MAX_CONTENT_TYPES_BYTES = 1024 * 1024;

    private final MimeTypes mimeTypes = MimeTypes.getDefaultMimeTypes();

    @Override
    public DetectedAttachmentContent detect(Path sealedContent, AttachmentFileName fileName)
            throws IOException {
        if (!Files.isRegularFile(sealedContent, LinkOption.NOFOLLOW_LINKS)) {
            throw rejected();
        }
        if (looksLikeZip(sealedContent)) {
            AttachmentFileType zipType = classifyZip(sealedContent);
            return new DetectedAttachmentContent(zipType, zipType.canonicalMime());
        }

        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName.displayName());
        MediaType detected;
        try (InputStream input = new BufferedInputStream(
                Files.newInputStream(
                        sealedContent,
                        java.nio.file.StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS
                ),
                AttachmentUploadPolicy.BUFFER_BYTES
        )) {
            detected = mimeTypes.detect(input, metadata);
        }
        String mime = detected.toString().toLowerCase(Locale.ROOT);
        AttachmentFileType type = switch (mime) {
            case "application/pdf" -> AttachmentFileType.PDF;
            case "image/png" -> AttachmentFileType.PNG;
            case "image/jpeg" -> AttachmentFileType.JPEG;
            case "image/gif" -> AttachmentFileType.GIF;
            default -> null;
        };
        if (type != null) {
            return new DetectedAttachmentContent(type, type.canonicalMime());
        }
        if (fileName.expectedType().isText() && isSafeUtf8Text(sealedContent)) {
            return new DetectedAttachmentContent(
                    fileName.expectedType(),
                    fileName.expectedType().canonicalMime()
            );
        }
        throw rejected();
    }

    private static AttachmentFileType classifyZip(Path path) throws IOException {
        Set<String> names = new HashSet<>();
        String contentTypes = "";
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                if (++count > MAX_ZIP_ENTRIES) {
                    throw rejected();
                }
                ZipEntry entry = entries.nextElement();
                String normalized = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                names.add(normalized);
                if (normalized.endsWith("vbaproject.bin")) {
                    throw rejected();
                }
                if ("[content_types].xml".equals(normalized)) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        contentTypes = readBoundedUtf8(input, MAX_CONTENT_TYPES_BYTES);
                    }
                }
            }
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }

        String normalizedTypes = contentTypes.toLowerCase(Locale.ROOT);
        if (names.contains("word/document.xml") && normalizedTypes.contains(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        )) {
            return AttachmentFileType.DOCX;
        }
        if (names.contains("xl/workbook.xml") && normalizedTypes.contains(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
        )) {
            return AttachmentFileType.XLSX;
        }
        if (names.contains("ppt/presentation.xml") && normalizedTypes.contains(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
        )) {
            return AttachmentFileType.PPTX;
        }
        return AttachmentFileType.ZIP;
    }

    private static boolean looksLikeZip(Path path) throws IOException {
        byte[] signature = new byte[4];
        try (InputStream input = Files.newInputStream(
                path,
                java.nio.file.StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            if (input.read(signature) != signature.length) {
                return false;
            }
        }
        return signature[0] == 'P' && signature[1] == 'K'
                && ((signature[2] == 3 && signature[3] == 4)
                || (signature[2] == 5 && signature[3] == 6)
                || (signature[2] == 7 && signature[3] == 8));
    }

    private static boolean isSafeUtf8Text(Path path) throws IOException {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (Reader reader = new InputStreamReader(
                    Files.newInputStream(
                            path,
                            java.nio.file.StandardOpenOption.READ,
                            LinkOption.NOFOLLOW_LINKS
                    ),
                    decoder
            )) {
                char[] characters = new char[TEXT_SAMPLE_BYTES / Character.BYTES];
                boolean sawContent = false;
                int read;
                while ((read = reader.read(characters)) != -1) {
                    sawContent = true;
                    for (int index = 0; index < read; index++) {
                        char character = characters[index];
                        if (character == 0 || (Character.isISOControl(character)
                                && character != '\t'
                                && character != '\r'
                                && character != '\n')) {
                            return false;
                        }
                    }
                }
                return sawContent;
            }
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static String readBoundedUtf8(InputStream input, int limit) throws IOException {
        byte[] buffer = new byte[Math.min(AttachmentUploadPolicy.BUFFER_BYTES, limit + 1)];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw rejected();
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static UploadRejectedException rejected() {
        return new UploadRejectedException(AttachmentRejectedCode.FILE_TYPE_NOT_ALLOWED);
    }
}
