package com.yumpoo.platform.filestorage.infrastructure.backup;

import com.yumpoo.platform.filestorage.infrastructure.backup.M017BackupManifest.FileRecord;
import com.yumpoo.platform.filestorage.infrastructure.backup.M017BackupManifest.FileRole;
import com.yumpoo.platform.filestorage.infrastructure.backup.M017BackupManifest.RetentionMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** M0-17 测试层备份集组装与恢复前验证，不进入生产制品。 */
public final class M017BackupSet {

    public static final String MANIFEST_FILE = "manifest.json";
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SOURCE_COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern SECRET_VARIABLE = Pattern.compile("^[A-Z][A-Z0-9_]{2,127}$");
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper();

    private M017BackupSet() {
    }

    public static Path create(CreateRequest request) throws IOException {
        Objects.requireNonNull(request, "request must not be null");
        validateCreateRequest(request);
        Path parent = prepareDirectory(request.outputParent());
        Path finalRoot = parent.resolve("backup-set");
        Path partialRoot = parent.resolve("backup-set.partial");
        if (Files.exists(finalRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(partialRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("backup output already exists");
        }
        Files.createDirectory(partialRoot);
        boolean completed = false;
        try {
            copyRegularFile(request.databaseDump(), partialRoot.resolve("database/yumpoo.dump"));
            copyAttachmentTree(request.attachmentRoot(), partialRoot.resolve("attachments"));
            copyRegularFile(request.configuration(), partialRoot.resolve("configuration/application.yml"));
            copyRegularFile(request.restoreDescriptor(), partialRoot.resolve("recovery/secret-recovery.json"));

            List<FileRecord> records = collectPayloadRecords(partialRoot);
            M017BackupManifest manifest = new M017BackupManifest(
                    1,
                    "M0-17",
                    request.backupSetId(),
                    request.createdAt(),
                    request.companyTimeZone(),
                    request.applicationVersion(),
                    request.sourceCommit(),
                    request.postgresVersion(),
                    request.flywaySchemaVersion(),
                    "database/yumpoo.dump",
                    "recovery/secret-recovery.json",
                    new RetentionMetadata(List.copyOf(request.retentionLabels()), request.legalHold()),
                    records
            );
            JSON.writerWithDefaultPrettyPrinter().writeValue(partialRoot.resolve(MANIFEST_FILE).toFile(), manifest);
            validate(partialRoot);
            moveCompleted(partialRoot, finalRoot);
            completed = true;
            return finalRoot;
        } finally {
            if (!completed) {
                deleteTreeIfExists(partialRoot);
            }
        }
    }

    public static M017BackupManifest validate(Path backupRoot) throws IOException {
        Path root = requireRealDirectory(backupRoot, "backupRoot");
        Path manifestPath = contained(root, root.resolve(MANIFEST_FILE));
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("backup manifest is missing");
        }
        M017BackupManifest manifest = JSON.readValue(manifestPath.toFile(), M017BackupManifest.class);
        validateManifestShape(manifest);

        Map<String, FileRecord> expected = new LinkedHashMap<>();
        Set<String> windowsFoldedPaths = new HashSet<>();
        String previous = null;
        for (FileRecord record : manifest.files()) {
            validateRecord(record);
            if (previous != null && previous.compareTo(record.path()) >= 0) {
                throw new IOException("manifest files must be strictly sorted");
            }
            previous = record.path();
            if (expected.put(record.path(), record) != null
                    || !windowsFoldedPaths.add(record.path().toLowerCase(Locale.ROOT))) {
                throw new IOException("manifest contains duplicate Windows paths");
            }
            validateRolePath(record);
        }

        Map<String, Path> actual = collectPayloadFiles(root);
        if (!actual.keySet().equals(expected.keySet())) {
            throw new IOException("manifest does not exactly cover backup payload");
        }
        for (Map.Entry<String, FileRecord> entry : expected.entrySet()) {
            Path file = actual.get(entry.getKey());
            FileRecord record = entry.getValue();
            if (Files.size(file) != record.bytes() || !sha256(file).equals(record.sha256())) {
                throw new IOException("backup payload integrity check failed: " + record.path());
            }
        }
        validateRestoreDescriptor(root.resolve(manifest.restoreDescriptorPath()));
        return manifest;
    }

    public static void requireEmptyAttachmentTarget(Path target) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Path absolute = target.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            Path real = requireRealDirectory(absolute, "attachmentTarget");
            try (Stream<Path> entries = Files.list(real)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("attachment restore target must be empty");
                }
            }
        } else {
            Files.createDirectories(absolute);
        }
    }

    public static void restoreAttachments(Path backupRoot, Path target) throws IOException {
        M017BackupManifest manifest = validate(backupRoot);
        requireEmptyAttachmentTarget(target);
        Path targetRoot = requireRealDirectory(target, "attachmentTarget");
        Path sourceRoot = requireRealDirectory(backupRoot, "backupRoot");
        for (FileRecord record : manifest.files()) {
            if (record.role() != FileRole.ATTACHMENT_BLOB) {
                continue;
            }
            String relative = record.path().substring("attachments/".length());
            copyRegularFile(sourceRoot.resolve(record.path()), targetRoot.resolve(relative));
        }
    }

    public static ObjectMapper json() {
        return JSON;
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    private static void validateCreateRequest(CreateRequest request) throws IOException {
        Objects.requireNonNull(request.backupSetId(), "backupSetId must not be null");
        Objects.requireNonNull(request.createdAt(), "createdAt must not be null");
        Objects.requireNonNull(request.retentionLabels(), "retentionLabels must not be null");
        if (!List.of("daily", "weekly", "monthly").containsAll(request.retentionLabels())) {
            throw new IOException("retention labels are invalid");
        }
        if (!"Asia/Shanghai".equals(request.companyTimeZone())) {
            throw new IOException("M0-17 default company timezone must be Asia/Shanghai");
        }
        if (!SOURCE_COMMIT.matcher(request.sourceCommit()).matches()) {
            throw new IOException("source commit must be a full lowercase Git hash");
        }
        requireRegularFile(request.databaseDump(), "databaseDump");
        requireRealDirectory(request.attachmentRoot(), "attachmentRoot");
        requireRegularFile(request.configuration(), "configuration");
        requireRegularFile(request.restoreDescriptor(), "restoreDescriptor");
        validateRestoreDescriptor(request.restoreDescriptor());
    }

    private static void validateManifestShape(M017BackupManifest manifest) throws IOException {
        if (manifest == null || manifest.schemaVersion() != 1 || !"M0-17".equals(manifest.milestone())
                || manifest.backupSetId() == null || manifest.createdAt() == null
                || !"Asia/Shanghai".equals(manifest.companyTimeZone())
                || manifest.applicationVersion() == null || manifest.applicationVersion().isBlank()
                || manifest.sourceCommit() == null || !SOURCE_COMMIT.matcher(manifest.sourceCommit()).matches()
                || manifest.postgresVersion() == null || !manifest.postgresVersion().startsWith("17.")
                || manifest.flywaySchemaVersion() == null || manifest.flywaySchemaVersion().isBlank()
                || !"database/yumpoo.dump".equals(manifest.databaseDumpPath())
                || !"recovery/secret-recovery.json".equals(manifest.restoreDescriptorPath())
                || manifest.retention() == null || manifest.files() == null || manifest.files().isEmpty()) {
            throw new IOException("backup manifest shape is invalid");
        }
        if (!List.of("daily", "weekly", "monthly").containsAll(manifest.retention().labels())) {
            throw new IOException("manifest retention labels are invalid");
        }
    }

    private static void validateRecord(FileRecord record) throws IOException {
        if (record == null || record.role() == null || record.bytes() < 0
                || record.sha256() == null || !SHA_256.matcher(record.sha256()).matches()) {
            throw new IOException("manifest file record is invalid");
        }
        safeRelativePath(record.path());
    }

    private static void validateRolePath(FileRecord record) throws IOException {
        boolean valid = switch (record.role()) {
            case DATABASE_DUMP -> record.path().equals("database/yumpoo.dump");
            case ATTACHMENT_BLOB -> record.path().matches(
                    "^attachments/sha256/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}$"
            ) && record.path().endsWith("/" + record.sha256());
            case CONFIGURATION -> record.path().equals("configuration/application.yml");
            case RESTORE_DESCRIPTOR -> record.path().equals("recovery/secret-recovery.json");
        };
        if (!valid) {
            throw new IOException("manifest role/path mismatch: " + record.path());
        }
    }

    private static void validateRestoreDescriptor(Path descriptor) throws IOException {
        JsonNode root = JSON.readTree(requireRegularFile(descriptor, "restoreDescriptor").toFile());
        if (!root.isObject() || root.size() != 4 || root.path("schemaVersion").asInt() != 1
                || root.path("secretValuesIncluded").asBoolean(true)) {
            throw new IOException("restore descriptor must explicitly exclude secret values");
        }
        JsonNode variables = root.path("requiredSecretVariables");
        JsonNode process = root.path("recoveryProcess");
        if (!variables.isArray() || variables.isEmpty() || !process.isTextual() || process.asText().isBlank()) {
            throw new IOException("restore descriptor is incomplete");
        }
        for (JsonNode variable : variables) {
            if (!variable.isTextual() || !SECRET_VARIABLE.matcher(variable.asText()).matches()) {
                throw new IOException("restore descriptor variable name is invalid");
            }
        }
    }

    private static List<FileRecord> collectPayloadRecords(Path root) throws IOException {
        Map<String, Path> files = collectPayloadFiles(root);
        List<FileRecord> records = new ArrayList<>();
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            records.add(new FileRecord(
                    entry.getKey(),
                    roleFor(entry.getKey()),
                    Files.size(entry.getValue()),
                    sha256(entry.getValue())
            ));
        }
        records.sort(Comparator.comparing(FileRecord::path));
        return List.copyOf(records);
    }

    private static Map<String, Path> collectPayloadFiles(Path root) throws IOException {
        Map<String, Path> files = new HashMap<>();
        Set<String> windowsFoldedPaths = new HashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(dir)) {
                    throw new IOException("symbolic links are forbidden in backup sets");
                }
                contained(root, dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("backup set contains an unsupported file");
                }
                String relative = portable(root.relativize(file));
                if (!relative.equals(MANIFEST_FILE)) {
                    safeRelativePath(relative);
                    if (!windowsFoldedPaths.add(relative.toLowerCase(Locale.ROOT))) {
                        throw new IOException("backup set contains duplicate Windows paths");
                    }
                    files.put(relative, file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static FileRole roleFor(String relative) throws IOException {
        if (relative.equals("database/yumpoo.dump")) {
            return FileRole.DATABASE_DUMP;
        }
        if (relative.startsWith("attachments/")) {
            return FileRole.ATTACHMENT_BLOB;
        }
        if (relative.equals("configuration/application.yml")) {
            return FileRole.CONFIGURATION;
        }
        if (relative.equals("recovery/secret-recovery.json")) {
            return FileRole.RESTORE_DESCRIPTOR;
        }
        throw new IOException("backup payload is not allowlisted: " + relative);
    }

    private static void copyAttachmentTree(Path sourceRoot, Path targetRoot) throws IOException {
        Path realSource = requireRealDirectory(sourceRoot, "attachmentRoot");
        Files.walkFileTree(realSource, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(dir)) {
                    throw new IOException("attachment source may not contain symbolic links");
                }
                Path relative = realSource.relativize(dir);
                Files.createDirectories(targetRoot.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("attachment source contains an unsupported file");
                }
                copyRegularFile(file, targetRoot.resolve(realSource.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyRegularFile(Path source, Path target) throws IOException {
        requireRegularFile(source, "copySource");
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static Path safeRelativePath(String value) throws IOException {
        if (value == null || value.isBlank() || value.contains("\\") || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*") || value.contains("//")) {
            throw new IOException("manifest path is not a portable relative path");
        }
        Path path = Path.of(value);
        if (path.isAbsolute() || path.normalize().startsWith("..") || !portable(path.normalize()).equals(value)) {
            throw new IOException("manifest path escapes or is not normalized");
        }
        return path;
    }

    private static Path prepareDirectory(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Files.createDirectories(directory.toAbsolutePath().normalize());
        return requireRealDirectory(directory, "outputParent");
    }

    private static Path requireRealDirectory(Path directory, String label) throws IOException {
        Objects.requireNonNull(directory, label + " must not be null");
        Path absolute = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a real directory");
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static Path requireRegularFile(Path file, String label) throws IOException {
        Objects.requireNonNull(file, label + " must not be null");
        Path absolute = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a regular file");
        }
        return absolute;
    }

    private static Path contained(Path root, Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException("backup path escaped its root");
        }
        return normalized;
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void moveCompleted(Path partialRoot, Path finalRoot) throws IOException {
        try {
            Files.move(partialRoot, finalRoot, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("backup set finalization requires an atomic same-volume move", exception);
        }
    }

    public static void deleteTreeIfExists(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path resolved = root.toAbsolutePath().normalize();
        String name = resolved.getFileName().toString();
        if (!(name.equals("m0-17") || name.equals("backup-set.partial")
                || name.startsWith("m017-"))) {
            throw new IOException("refusing to delete an unexpected M0-17 path");
        }
        try (Stream<Path> paths = Files.walk(resolved)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(resolved)) {
                    throw new IOException("M0-17 cleanup escaped its root");
                }
                Files.deleteIfExists(path);
            }
        }
    }

    public record CreateRequest(
            Path outputParent,
            UUID backupSetId,
            Instant createdAt,
            String companyTimeZone,
            String applicationVersion,
            String sourceCommit,
            String postgresVersion,
            String flywaySchemaVersion,
            Path databaseDump,
            Path attachmentRoot,
            Path configuration,
            Path restoreDescriptor,
            List<String> retentionLabels,
            boolean legalHold
    ) {
    }
}
