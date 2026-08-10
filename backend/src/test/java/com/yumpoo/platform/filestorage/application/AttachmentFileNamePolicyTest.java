package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentFileType;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentFileNamePolicyTest {

    @Test
    void sanitizesDisplayNameWithoutUsingItAsAPath() {
        AttachmentFileName result = AttachmentFileNamePolicy.normalize("../部门\\报告:最终.PDF ");

        assertThat(result.displayName()).isEqualTo(".._部门_报告_最终.PDF");
        assertThat(result.extension()).isEqualTo("pdf");
        assertThat(result.expectedType()).isEqualTo(AttachmentFileType.PDF);
    }

    @Test
    void supportsDocumentedAliases() {
        assertThat(AttachmentFileNamePolicy.normalize("photo.jpeg").expectedType())
                .isEqualTo(AttachmentFileType.JPEG);
        assertThat(AttachmentFileNamePolicy.normalize("notes.markdown").expectedType())
                .isEqualTo(AttachmentFileType.MARKDOWN);
    }

    @Test
    void removesBidirectionalFormatControlsFromDisplayName() {
        AttachmentFileName normalized = AttachmentFileNamePolicy.normalize(
                "quarterly\u202Efdp.pdf"
        );

        assertThat(normalized.displayName()).isEqualTo("quarterlyfdp.pdf");
    }

    @Test
    void rejectsDangerousInnerAndFinalExtensions() {
        assertTypeRejected("invoice.exe.pdf");
        assertTypeRejected("invoice.pdf.exe");
        assertTypeRejected("macro.docm");
        assertTypeRejected("script.js.txt");
        assertTypeRejected("no-extension");
    }

    private static void assertTypeRejected(String fileName) {
        assertThatThrownBy(() -> AttachmentFileNamePolicy.normalize(fileName))
                .isInstanceOf(UploadRejectedException.class)
                .extracting(exception -> ((UploadRejectedException) exception).rejectedCode())
                .isEqualTo(AttachmentRejectedCode.FILE_TYPE_NOT_ALLOWED);
    }
}
