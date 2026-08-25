package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.AttachmentContentDetector;
import com.yumpoo.platform.filestorage.application.MalwareScanner;
import com.yumpoo.platform.filestorage.application.MalwareScanVerdict;
import com.yumpoo.platform.filestorage.application.QuarantineStorage;
import com.yumpoo.platform.filestorage.application.AttachmentRuntimeSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttachmentProperties.class)
public class AttachmentRuntimeConfiguration {
    @Bean
    AttachmentRuntimeSettings attachmentRuntimeSettings(AttachmentProperties properties) {
        return new AttachmentRuntimeSettings(properties.getCompanyQuotaBytes(),
                properties.getProjectQuotaBytes(), properties.getScanLease(),
                properties.getUploadLease(), properties.getFirstScanRetry(),
                properties.getSecondScanRetry());
    }
    @Bean
    QuarantineStorage attachmentStorage(AttachmentProperties properties,
            Environment environment) throws IOException {
        Path uploadRoot = Path.of(properties.getUploadTempRoot());
        Path attachmentRoot = Path.of(properties.getAttachmentRoot());
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            requirePrevalidatedDirectory(uploadRoot, "upload-temp-root");
            requirePrevalidatedDirectory(attachmentRoot, "attachment-root");
        }
        return new LocalFileQuarantineStorage(uploadRoot, attachmentRoot);
    }

    @Bean
    AttachmentContentDetector attachmentContentDetector() {
        return new TikaAttachmentContentDetector();
    }

    @Bean
    MalwareScanner attachmentMalwareScanner(AttachmentProperties properties,
            Environment environment) {
        String configured = properties.getDefenderExecutable();
        if (configured != null && !configured.isBlank()) {
            Path executable = Path.of(configured).toAbsolutePath().normalize();
            if (!Files.isRegularFile(executable)) {
                throw new IllegalStateException("configured Defender executable is unavailable");
            }
            return new DefenderMpCmdRunScanner(executable, properties.getDefenderTimeout());
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("production requires yumpoo.attachments.defender-executable");
        }
        return ignored -> MalwareScanVerdict.UNAVAILABLE;
    }

    private static void requirePrevalidatedDirectory(Path directory, String property) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IllegalStateException("production attachment " + property
                    + " must be an existing real directory");
        }
    }
}
