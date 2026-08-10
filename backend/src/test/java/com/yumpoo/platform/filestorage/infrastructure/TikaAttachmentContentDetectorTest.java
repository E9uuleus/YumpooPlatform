package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.AttachmentFileName;
import com.yumpoo.platform.filestorage.application.AttachmentFileNamePolicy;
import com.yumpoo.platform.filestorage.application.DetectedAttachmentContent;
import com.yumpoo.platform.filestorage.application.UploadRejectedException;
import com.yumpoo.platform.filestorage.domain.AttachmentFileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TikaAttachmentContentDetectorTest {

    @TempDir
    private Path tempDirectory;

    private final TikaAttachmentContentDetector detector = new TikaAttachmentContentDetector();

    @Test
    void detectsEveryFrozenDefaultType() throws Exception {
        assertDetected("sample.pdf", bytes("%PDF-1.7\n1 0 obj\n%%EOF"), AttachmentFileType.PDF);
        assertDetected("sample.png", hex("89504e470d0a1a0a0000000d49484452"), AttachmentFileType.PNG);
        assertDetected("sample.jpg", hex("ffd8ffe000104a4649460001"), AttachmentFileType.JPEG);
        assertDetected("sample.gif", bytes("GIF89a........"), AttachmentFileType.GIF);
        assertDetected("sample.txt", bytes("plain UTF-8 文本"), AttachmentFileType.TXT);
        assertDetected("sample.csv", bytes("name,value\nalpha,1\n"), AttachmentFileType.CSV);
        assertDetected("sample.md", bytes("# 标题\n\n正文"), AttachmentFileType.MARKDOWN);

        assertZipDetected("sample.zip", Map.of("readme.txt", bytes("safe")), AttachmentFileType.ZIP);
        assertZipDetected("sample.docx", officeEntries(
                "word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        ), AttachmentFileType.DOCX);
        assertZipDetected("sample.xlsx", officeEntries(
                "xl/workbook.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
        ), AttachmentFileType.XLSX);
        assertZipDetected("sample.pptx", officeEntries(
                "ppt/presentation.xml",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
        ), AttachmentFileType.PPTX);
    }

    @Test
    void identifiesOfficeContainerEvenWhenExtensionClaimsZip() throws Exception {
        Path path = zip("spoof.zip", officeEntries(
                "word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        ));

        DetectedAttachmentContent detected = detector.detect(
                path,
                AttachmentFileNamePolicy.normalize("spoof.zip")
        );

        assertThat(detected.fileType()).isEqualTo(AttachmentFileType.DOCX);
    }

    @Test
    void rejectsMacroEnabledOfficeContainerAndBinaryText() throws Exception {
        Map<String, byte[]> macro = officeEntries(
                "word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        );
        macro.put("word/vbaProject.bin", new byte[]{1, 2, 3});
        Path macroPath = zip("macro.docx", macro);

        assertThatThrownBy(() -> detector.detect(
                macroPath,
                AttachmentFileNamePolicy.normalize("macro.docx")
        )).isInstanceOf(UploadRejectedException.class);

        Path binary = tempDirectory.resolve("binary.txt");
        Files.write(binary, new byte[]{'a', 0, 'b'});
        assertThatThrownBy(() -> detector.detect(
                binary,
                AttachmentFileNamePolicy.normalize("binary.txt")
        )).isInstanceOf(UploadRejectedException.class);

        byte[] lateBinary = new byte[70 * 1024];
        Arrays.fill(lateBinary, (byte) 'a');
        lateBinary[lateBinary.length - 2] = (byte) 0xc3;
        lateBinary[lateBinary.length - 1] = 0x28;
        Path lateBinaryPath = tempDirectory.resolve("late-binary.txt");
        Files.write(lateBinaryPath, lateBinary);
        assertThatThrownBy(() -> detector.detect(
                lateBinaryPath,
                AttachmentFileNamePolicy.normalize("late-binary.txt")
        )).isInstanceOf(UploadRejectedException.class);
    }

    private void assertDetected(String name, byte[] content, AttachmentFileType expected) throws Exception {
        Path path = tempDirectory.resolve(name);
        Files.write(path, content);
        AttachmentFileName fileName = AttachmentFileNamePolicy.normalize(name);

        assertThat(detector.detect(path, fileName).fileType()).isEqualTo(expected);
    }

    private void assertZipDetected(
            String name,
            Map<String, byte[]> entries,
            AttachmentFileType expected
    ) throws Exception {
        Path path = zip(name, entries);
        AttachmentFileName fileName = AttachmentFileNamePolicy.normalize(name);
        assertThat(detector.detect(path, fileName).fileType()).isEqualTo(expected);
    }

    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        Path path = tempDirectory.resolve(name);
        try (OutputStream output = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    private static Map<String, byte[]> officeEntries(String mainPart, String contentType) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", bytes(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Types><Override PartName=\"/" + mainPart + "\" ContentType=\""
                        + contentType + "\"/></Types>"
        ));
        entries.put("_rels/.rels", bytes("<Relationships/>"));
        entries.put(mainPart, bytes("<root/>"));
        return entries;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }
}
