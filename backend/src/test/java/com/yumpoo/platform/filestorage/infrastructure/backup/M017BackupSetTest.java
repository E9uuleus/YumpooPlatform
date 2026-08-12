package com.yumpoo.platform.filestorage.infrastructure.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M017BackupSetTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void createsStrictCompleteSetAndRestoresOnlyAttachmentBlobs() throws Exception {
        Path backup = createBackup(tempDirectory.resolve("complete"));
        M017BackupManifest manifest = M017BackupSet.validate(backup);
        Path restore = tempDirectory.resolve("restore");

        M017BackupSet.restoreAttachments(backup, restore);

        assertThat(manifest.files()).extracting(M017BackupManifest.FileRecord::path)
                .containsExactly(
                        attachmentPath("synthetic attachment"),
                        "configuration/application.yml",
                        "database/yumpoo.dump",
                        "recovery/secret-recovery.json"
                );
        assertThat(Files.readString(
                restore.resolve(attachmentPath("synthetic attachment").substring("attachments/".length())),
                StandardCharsets.UTF_8
        )).isEqualTo("synthetic attachment");
        assertThat(Files.exists(backup.resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(backup.getParent().resolve("backup-set.partial"))).isFalse();
    }

    @Test
    void failsClosedForMissingExtraAndTamperedPayloads() throws Exception {
        Path missing = createBackup(tempDirectory.resolve("missing"));
        Files.delete(missing.resolve("database/yumpoo.dump"));
        assertThatThrownBy(() -> M017BackupSet.validate(missing))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exactly cover");

        Path extra = createBackup(tempDirectory.resolve("extra"));
        Files.writeString(extra.resolve("unexpected.txt"), "not allowlisted", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> M017BackupSet.validate(extra))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exactly cover");

        Path tampered = createBackup(tempDirectory.resolve("tampered"));
        Files.writeString(tampered.resolve("database/yumpoo.dump"), "changed", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> M017BackupSet.validate(tampered))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void rejectsDangerousAndWindowsCollidingManifestPaths() throws Exception {
        Path traversal = createBackup(tempDirectory.resolve("traversal"));
        ObjectNode traversalManifest = manifest(traversal);
        ((ObjectNode) traversalManifest.withArray("files").get(0)).put("path", "../outside");
        writeManifest(traversal, traversalManifest);
        assertThatThrownBy(() -> M017BackupSet.validate(traversal))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes");

        Path absolute = createBackup(tempDirectory.resolve("absolute"));
        ObjectNode absoluteManifest = manifest(absolute);
        ((ObjectNode) absoluteManifest.withArray("files").get(0)).put("path", "C:/backup/file");
        writeManifest(absolute, absoluteManifest);
        assertThatThrownBy(() -> M017BackupSet.validate(absolute))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("relative");

        Path collision = createBackup(tempDirectory.resolve("collision"));
        ObjectNode collisionManifest = manifest(collision);
        ArrayNode files = collisionManifest.withArray("files");
        ObjectNode duplicate = (ObjectNode) files.get(0).deepCopy();
        duplicate.put("path", duplicate.path("path").asText().toUpperCase());
        files.add(duplicate);
        writeManifest(collision, collisionManifest);
        assertThatThrownBy(() -> M017BackupSet.validate(collision))
                .isInstanceOf(IOException.class)
                .satisfies(exception -> assertThat(exception.getMessage())
                        .containsAnyOf("sorted", "duplicate Windows"));
    }

    @Test
    void rejectsSecretValuesAndNonEmptyRestoreTargets() throws Exception {
        Path fixture = fixture(tempDirectory.resolve("secret"));
        Files.writeString(
                fixture.resolve("secret-recovery.json"),
                "{\"schemaVersion\":1,\"secretValuesIncluded\":true,"
                        + "\"requiredSecretVariables\":[\"DATABASE_PASSWORD\"],"
                        + "\"recoveryProcess\":\"external\"}",
                StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> M017BackupSet.create(request(fixture)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exclude secret values");

        Path backup = createBackup(tempDirectory.resolve("target"));
        Path restore = Files.createDirectories(tempDirectory.resolve("nonempty"));
        Files.writeString(restore.resolve("existing"), "keep", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> M017BackupSet.restoreAttachments(backup, restore))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("must be empty");
        assertThat(Files.readString(restore.resolve("existing"), StandardCharsets.UTF_8)).isEqualTo("keep");
    }

    @Test
    void rejectsSymbolicLinksInAttachmentSourcesAndBackupSets() throws Exception {
        Path fixture = fixture(tempDirectory.resolve("links/fixture"));
        Path external = Files.writeString(
                tempDirectory.resolve("links/external"),
                "external",
                StandardCharsets.UTF_8
        );
        Path link = fixture.resolve("blobs/link");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("当前文件系统不允许创建符号链接");
        }

        assertThatThrownBy(() -> M017BackupSet.create(request(fixture)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symbolic links");
    }

    private Path createBackup(Path root) throws Exception {
        Path fixture = fixture(root.resolve("fixture"));
        return M017BackupSet.create(request(fixture));
    }

    private Path fixture(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("yumpoo.dump"), "synthetic database dump", StandardCharsets.UTF_8);
        Path blob = root.resolve("blobs")
                .resolve(attachmentPath("synthetic attachment").substring("attachments/".length()));
        Files.createDirectories(blob.getParent());
        Files.writeString(blob, "synthetic attachment", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("application.yml"), "server:\n  address: 127.0.0.1\n", StandardCharsets.UTF_8);
        Files.writeString(
                root.resolve("secret-recovery.json"),
                "{\"schemaVersion\":1,\"secretValuesIncluded\":false,"
                        + "\"requiredSecretVariables\":[\"DATABASE_PASSWORD\"],"
                        + "\"recoveryProcess\":\"restore from the approved external secret store\"}",
                StandardCharsets.UTF_8
        );
        return root;
    }

    private M017BackupSet.CreateRequest request(Path fixture) {
        return new M017BackupSet.CreateRequest(
                fixture.getParent(),
                UUID.fromString("00000000-0000-4000-8000-000000000017"),
                Instant.parse("2026-08-12T12:00:00Z"),
                "Asia/Shanghai",
                "0.0.1-SNAPSHOT",
                "0".repeat(40),
                "17.10",
                "5",
                fixture.resolve("yumpoo.dump"),
                fixture.resolve("blobs"),
                fixture.resolve("application.yml"),
                fixture.resolve("secret-recovery.json"),
                List.of("daily"),
                false
        );
    }

    private ObjectNode manifest(Path backup) throws IOException {
        return (ObjectNode) M017BackupSet.json().readTree(backup.resolve("manifest.json").toFile());
    }

    private void writeManifest(Path backup, JsonNode manifest) throws IOException {
        M017BackupSet.json().writerWithDefaultPrettyPrinter()
                .writeValue(backup.resolve("manifest.json").toFile(), manifest);
    }

    private static String attachmentPath(String content) throws IOException {
        Path temporary = Files.createTempFile("m017-digest-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            String digest = M017BackupSet.sha256(temporary);
            return "attachments/sha256/" + digest.substring(0, 2) + "/"
                    + digest.substring(2, 4) + "/" + digest;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
