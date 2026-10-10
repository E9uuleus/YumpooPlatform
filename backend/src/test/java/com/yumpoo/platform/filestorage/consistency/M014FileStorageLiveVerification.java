package com.yumpoo.platform.filestorage.consistency;

import com.yumpoo.platform.filestorage.application.AttachmentUploadPolicy;
import com.yumpoo.platform.filestorage.infrastructure.LocalFileQuarantineStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.Comparator;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "YUMPOO_M014_LIVE_ENABLED", matches = "true")
class M014FileStorageLiveVerification {
    private Path temporaryDirectory;

    @BeforeEach
    void validateTargetVolume() throws Exception {
        String configured = System.getenv("YUMPOO_M014_LIVE_ROOT");
        assertThat(configured).isNotBlank();
        Path root = Path.of(configured).toAbsolutePath().normalize();
        assertThat(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(root)).isFalse();
        assertThat(root.toRealPath()).isEqualTo(root);
        assertThat(Files.getFileStore(root).type()).isEqualToIgnoringCase("NTFS");
        assertThat(Files.getFileStore(root).getUsableSpace()).isGreaterThan(2 * AttachmentUploadPolicy.MAX_BYTES);
        temporaryDirectory = Files.createTempDirectory(root, "m014-storage-");
    }

    @AfterEach
    void removeOwnedFixture() throws Exception {
        if (temporaryDirectory == null) return;
        try (var entries = Files.walk(temporaryDirectory)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }

    @Test
    void verifiesNtfsSameVolumeAtomicPublicationAndIntegrity() throws Exception {
        Path quarantine = Files.createDirectory(temporaryDirectory.resolve("quarantine"));
        Path blobs = Files.createDirectory(temporaryDirectory.resolve("blobs"));
        assertThat(Files.getFileStore(quarantine).type()).isEqualToIgnoringCase("NTFS");
        assertThat(Files.getFileStore(quarantine)).isEqualTo(Files.getFileStore(blobs));
        LocalFileQuarantineStorage storage = new LocalFileQuarantineStorage(quarantine, blobs);
        byte[] bytes = "storage live verification".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var sealed = storage.receive(UUID.randomUUID(), new ByteArrayInputStream(bytes), OptionalLong.of(bytes.length));
        var published = storage.publish(sealed);
        assertThat(Files.exists(sealed.quarantinedPath())).isFalse();
        assertThat(storage.verify(published)).isTrue();
        try (var content = storage.open(published)) {
            assertThat(content.readAllBytes()).isEqualTo(bytes);
        }
        assertThat(AttachmentUploadPolicy.BUFFER_BYTES).isEqualTo(65_536);
    }
}
