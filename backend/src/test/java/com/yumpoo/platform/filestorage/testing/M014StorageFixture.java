package com.yumpoo.platform.filestorage.testing;

import com.yumpoo.platform.filestorage.infrastructure.LocalFileQuarantineStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** 为 M0-14 探针提供同卷的临时隔离区和正式 blob 区。 */
public final class M014StorageFixture implements AutoCloseable {

    private final Path baseRoot;
    private final Path quarantineRoot;
    private final Path blobRoot;
    private final LocalFileQuarantineStorage storage;

    public M014StorageFixture() throws IOException {
        Path target = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(target);
        baseRoot = Files.createTempDirectory(target, "m0-14-storage-")
                .toRealPath(LinkOption.NOFOLLOW_LINKS);
        quarantineRoot = Files.createDirectory(baseRoot.resolve("quarantine"));
        blobRoot = Files.createDirectory(baseRoot.resolve("blobs"));
        storage = new LocalFileQuarantineStorage(quarantineRoot, blobRoot);
    }

    public LocalFileQuarantineStorage storage() {
        return storage;
    }

    public Path quarantineRoot() {
        return quarantineRoot;
    }

    public Path blobRoot() {
        return blobRoot;
    }

    public long quarantineFileCount() throws IOException {
        return regularFileCount(quarantineRoot);
    }

    public long blobFileCount() throws IOException {
        return regularFileCount(blobRoot);
    }

    public void clear() throws IOException {
        deleteDescendants(quarantineRoot);
        deleteDescendants(blobRoot);
    }

    @Override
    public void close() throws IOException {
        if (!baseRoot.getFileName().toString().startsWith("m0-14-storage-")) {
            throw new IOException("refusing to delete an unexpected M0-14 fixture root");
        }
        deleteTree(baseRoot);
    }

    private static long regularFileCount(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).count();
        }
    }

    private static void deleteDescendants(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Path resolved = root.toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(resolved)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(resolved)) {
                    throw new IOException("fixture cleanup escaped its root");
                }
                Files.deleteIfExists(path);
            }
        }
    }
}
