package com.yumpoo.platform.filestorage.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttachmentRuntimeConfigurationTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void productionDoesNotCreateMissingStorageDirectories() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setUploadTempRoot(tempDirectory.resolve("missing-upload").toString());
        properties.setAttachmentRoot(tempDirectory.resolve("missing-attachments").toString());
        Environment environment = productionEnvironment();

        assertThatThrownBy(() -> new AttachmentRuntimeConfiguration()
                .attachmentStorage(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be an existing real directory");
    }

    @Test
    void productionStartsWithPrevalidatedStorageDirectories() throws Exception {
        AttachmentProperties properties = new AttachmentProperties();
        Path upload = Files.createDirectory(tempDirectory.resolve("upload"));
        Path attachments = Files.createDirectory(tempDirectory.resolve("attachments"));
        properties.setUploadTempRoot(upload.toString());
        properties.setAttachmentRoot(attachments.toString());
        Environment environment = productionEnvironment();

        org.assertj.core.api.Assertions.assertThat(new AttachmentRuntimeConfiguration()
                .attachmentStorage(properties, environment)).isNotNull();
        org.assertj.core.api.Assertions.assertThat(new AttachmentRuntimeConfiguration()
                .attachmentRuntimeSettings(properties)).isNotNull();
    }

    @Test
    void cleanupDeletionFailsClosedWithoutApprovalReference() {
        AttachmentProperties properties=new AttachmentProperties();
        properties.setCleanupDeleteEnabled(true);

        assertThatThrownBy(()->new AttachmentRuntimeConfiguration().attachmentRuntimeSettings(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approval reference");
    }

    @Test
    void cleanupRemainsDryRunByDefault() {
        AttachmentProperties properties=new AttachmentProperties();
        new AttachmentRuntimeConfiguration().attachmentRuntimeSettings(properties);
        org.assertj.core.api.Assertions.assertThat(properties.isCleanupDeleteEnabled()).isFalse();
    }

    private static Environment productionEnvironment() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        return environment;
    }
}
