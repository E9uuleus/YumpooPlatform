package com.yumpoo.platform.filestorage.infrastructure.backup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record M017BackupManifest(
        int schemaVersion,
        String milestone,
        UUID backupSetId,
        Instant createdAt,
        String companyTimeZone,
        String applicationVersion,
        String sourceCommit,
        String postgresVersion,
        String flywaySchemaVersion,
        String databaseDumpPath,
        String restoreDescriptorPath,
        RetentionMetadata retention,
        List<FileRecord> files
) {
    public record RetentionMetadata(List<String> labels, boolean legalHold) {
    }

    public record FileRecord(String path, FileRole role, long bytes, String sha256) {
    }

    public enum FileRole {
        DATABASE_DUMP,
        ATTACHMENT_BLOB,
        CONFIGURATION,
        RESTORE_DESCRIPTOR
    }
}
