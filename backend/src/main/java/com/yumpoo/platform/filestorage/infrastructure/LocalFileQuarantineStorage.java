package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.AttachmentUploadPolicy;
import com.yumpoo.platform.filestorage.application.BlobVerification;
import com.yumpoo.platform.filestorage.application.PublishedBlob;
import com.yumpoo.platform.filestorage.application.QuarantineStorage;
import com.yumpoo.platform.filestorage.application.SealedUpload;
import com.yumpoo.platform.filestorage.application.UploadIncompleteException;
import com.yumpoo.platform.filestorage.application.UploadRejectedException;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.List;
import java.util.Comparator;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.UUID;
import java.util.regex.Pattern;

/** 本机隔离目录和内容寻址正式目录适配器。 */
public final class LocalFileQuarantineStorage implements QuarantineStorage {

    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern STORAGE_KEY = Pattern.compile(
            "^sha256/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}$"
    );

    private final Path quarantineRoot;
    private final Path blobRoot;

    public LocalFileQuarantineStorage(Path quarantineRoot, Path blobRoot) throws IOException {
        this.quarantineRoot = prepareRoot(quarantineRoot, "quarantineRoot");
        this.blobRoot = prepareRoot(blobRoot, "blobRoot");
        FileStore quarantineStore = Files.getFileStore(this.quarantineRoot);
        FileStore publishedStore = Files.getFileStore(this.blobRoot);
        if (!quarantineStore.equals(publishedStore)) {
            throw new IllegalArgumentException("quarantineRoot and blobRoot must use the same FileStore");
        }
    }

    @Override
    public SealedUpload receive(
            UUID uploadId,
            InputStream source,
            OptionalLong contentLength
    ) throws IOException {
        return receive(uploadId, source, contentLength, AttachmentUploadPolicy.MAX_BYTES);
    }

    @Override
    public SealedUpload receive(
            UUID uploadId,
            InputStream source,
            OptionalLong contentLength,
            long reservationLimit
    ) throws IOException {
        Objects.requireNonNull(uploadId, "uploadId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(contentLength, "contentLength must not be null");
        if (reservationLimit < 0 || reservationLimit > AttachmentUploadPolicy.MAX_BYTES) {
            throw new IllegalArgumentException("reservationLimit is outside the allowed range");
        }
        if (contentLength.isPresent() && contentLength.orElseThrow() > AttachmentUploadPolicy.MAX_BYTES) {
            throw new UploadRejectedException(AttachmentRejectedCode.FILE_TOO_LARGE);
        }
        if (contentLength.isPresent() && contentLength.orElseThrow() > reservationLimit) {
            throw new UploadRejectedException(AttachmentRejectedCode.QUOTA_EXCEEDED);
        }

        Path part = quarantinePath(uploadId, ".part");
        Path sealed = quarantinePath(uploadId, ".sealed");
        if (Files.exists(sealed, LinkOption.NOFOLLOW_LINKS)) {
            throw new UploadIncompleteException();
        }
        Files.deleteIfExists(part);
        MessageDigest digest = sha256Digest();
        long total = 0;
        boolean sealedSuccessfully = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    part,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                byte[] buffer = new byte[AttachmentUploadPolicy.BUFFER_BYTES];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    total += read;
                    if (total > AttachmentUploadPolicy.MAX_BYTES) {
                        throw new UploadRejectedException(AttachmentRejectedCode.FILE_TOO_LARGE);
                    }
                    if (total > reservationLimit) {
                        throw new UploadRejectedException(AttachmentRejectedCode.QUOTA_EXCEEDED);
                    }
                    digest.update(buffer, 0, read);
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                }
                channel.force(true);
            }
            if (contentLength.isPresent() && total != contentLength.orElseThrow()) {
                throw new UploadIncompleteException();
            }
            if (total == 0) {
                throw new UploadRejectedException(AttachmentRejectedCode.FILE_TYPE_NOT_ALLOWED);
            }
            Files.move(part, sealed, StandardCopyOption.ATOMIC_MOVE);
            sealedSuccessfully = true;
            return new SealedUpload(uploadId, sealed, total, HEX.formatHex(digest.digest()));
        } catch (UploadRejectedException exception) {
            throw exception;
        } catch (AtomicMoveNotSupportedException exception) {
            throw new UploadIncompleteException();
        } catch (IOException exception) {
            throw new UploadIncompleteException();
        } finally {
            if (!sealedSuccessfully) {
                Files.deleteIfExists(part);
            }
        }
    }

    @Override
    public SealedUpload resume(UUID uploadId, long sizeBytes, String sha256) throws IOException {
        return new SealedUpload(uploadId, quarantinePath(uploadId, ".sealed"), sizeBytes, sha256);
    }

    @Override
    public PublishedBlob publish(SealedUpload upload) throws IOException {
        Objects.requireNonNull(upload, "upload must not be null");
        Path sealed = requireContained(quarantineRoot, upload.quarantinedPath());
        String storageKey = storageKey(upload.sha256());
        Path destination = resolveStorageKey(storageKey);
        prepareParent(blobRoot, destination.getParent());

        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            PublishedBlob existing = new PublishedBlob(
                    storageKey,
                    upload.sizeBytes(),
                    upload.sha256()
            );
            if (!verify(existing)) {
                throw new IOException("existing blob failed integrity verification");
            }
            Files.deleteIfExists(sealed);
            return existing;
        }
        if (!Files.isRegularFile(sealed, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("sealed upload is unavailable");
        }
        Files.move(sealed, destination, StandardCopyOption.ATOMIC_MOVE);
        PublishedBlob published = new PublishedBlob(storageKey, upload.sizeBytes(), upload.sha256());
        if (!verify(published)) {
            throw new IOException("published blob failed integrity verification");
        }
        return published;
    }

    @Override
    public InputStream open(PublishedBlob blob) throws IOException {
        Objects.requireNonNull(blob, "blob must not be null");
        Path path = resolveStorageKey(blob.storageKey());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("published blob is unavailable");
        }
        return new BufferedInputStream(
                Files.newInputStream(
                        path,
                        StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS
                ),
                AttachmentUploadPolicy.BUFFER_BYTES
        );
    }

    @Override
    public boolean verify(PublishedBlob blob) throws IOException {
        return inspect(blob) == BlobVerification.VERIFIED;
    }

    @Override
    public BlobVerification inspect(PublishedBlob blob) throws IOException {
        Path path = resolveStorageKey(blob.storageKey());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return BlobVerification.MISSING;
        }
        if (Files.size(path) != blob.sizeBytes()) {
            return BlobVerification.SIZE_MISMATCH;
        }
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            byte[] buffer = new byte[AttachmentUploadPolicy.BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return MessageDigest.isEqual(
                HEX.formatHex(digest.digest()).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                blob.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        ) ? BlobVerification.VERIFIED : BlobVerification.HASH_MISMATCH;
    }

    @Override
    public void discard(SealedUpload upload) {
        if (upload == null) {
            return;
        }
        try {
            Files.deleteIfExists(requireContained(quarantineRoot, upload.quarantinedPath()));
        } catch (IOException ignored) {
            // 清理任务会处理短期孤儿；不得因清理失败把内容发布为 AVAILABLE。
        }
    }

    @Override
    public void discard(PublishedBlob blob) {
        if (blob == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolveStorageKey(blob.storageKey()));
        } catch (IOException ignored) {
            // 测试/后续对账会处理安全孤儿。
        }
    }

    @Override
    public List<StorageEntry> listTemporary(String afterKey, int limit) throws IOException {
        try (Stream<Path> entries=Files.list(quarantineRoot)) {
            return entries.map(path->entry(quarantineRoot,path,false))
                    .sorted(Comparator.comparing(StorageEntry::key))
                    .filter(value->afterKey==null||value.key().compareTo(afterKey)>0)
                    .limit(limit).toList();
        }
    }

    @Override
    public List<StorageEntry> listPublished(String afterKey, int limit) throws IOException {
        try (Stream<Path> entries=Files.walk(blobRoot)) {
            return entries.filter(path->!path.equals(blobRoot))
                    .map(path->entry(blobRoot,path,true))
                    .filter(value->value.regularFile()||value.unsafeEntry())
                    .sorted(Comparator.comparing(StorageEntry::key))
                    .filter(value->afterKey==null||value.key().compareTo(afterKey)>0)
                    .limit(limit).toList();
        }
    }

    @Override
    public boolean deleteTemporary(String key) throws IOException {
        if(key==null||!key.matches("^[0-9a-fA-F-]{36}\\.(part|sealed)$")) return false;
        Path target=requireContained(quarantineRoot,quarantineRoot.resolve(key));
        if(Files.isSymbolicLink(target)||!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)) return false;
        return Files.deleteIfExists(target);
    }

    @Override
    public boolean deletePublished(String storageKey) throws IOException {
        Path target=resolveStorageKey(storageKey);
        if(Files.isSymbolicLink(target)||!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)) return false;
        return Files.deleteIfExists(target);
    }

    private static StorageEntry entry(Path root,Path path,boolean published) {
        String key=root.relativize(path).toString().replace('\\','/');
        try {
            boolean symbolic=Files.isSymbolicLink(path);
            boolean regular=Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS);
            boolean malformed=published&&regular&&!STORAGE_KEY.matcher(key).matches();
            return new StorageEntry(key,Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant(),
                    regular?Files.size(path):0,regular,
                    symbolic||malformed||(!regular&&!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)));
        } catch(IOException failure) {
            return new StorageEntry(key,Instant.EPOCH,0,false,true);
        }
    }

    private Path quarantinePath(UUID uploadId, String suffix) throws IOException {
        return requireContained(quarantineRoot, quarantineRoot.resolve(uploadId + suffix));
    }

    private Path resolveStorageKey(String storageKey) throws IOException {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw new IOException("storage key format is invalid");
        }
        String sha256 = storageKey.substring(storageKey.length() - 64);
        if (!storageKey.startsWith("sha256/" + sha256.substring(0, 2)
                + "/" + sha256.substring(2, 4) + "/")) {
            throw new IOException("storage key fan-out does not match digest");
        }
        return requireContained(
                blobRoot,
                blobRoot.resolve(storageKey.replace('/', java.io.File.separatorChar))
        );
    }

    private static String storageKey(String sha256) {
        return "sha256/" + sha256.substring(0, 2) + "/" + sha256.substring(2, 4) + "/" + sha256;
    }

    private static Path prepareRoot(Path configured, String label) throws IOException {
        Objects.requireNonNull(configured, label + " must not be null");
        Path absolute = configured.toAbsolutePath().normalize();
        Files.createDirectories(absolute);
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " must be a real directory");
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static void prepareParent(Path root, Path parent) throws IOException {
        requireContained(root, parent);
        Files.createDirectories(parent);
        requireContained(root, parent);
    }

    private static Path requireContained(Path root, Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("storage path escaped configured root");
        }
        Path parent = normalized.getParent();
        while (parent != null && parent.startsWith(root)) {
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(parent)) {
                    throw new IOException("symbolic links are not allowed in storage paths");
                }
                Path realParent = parent.toRealPath();
                if (!realParent.startsWith(root)) {
                    throw new IOException("reparse point escaped configured storage root");
                }
            }
            if (parent.equals(root)) {
                break;
            }
            parent = parent.getParent();
        }
        return normalized;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
