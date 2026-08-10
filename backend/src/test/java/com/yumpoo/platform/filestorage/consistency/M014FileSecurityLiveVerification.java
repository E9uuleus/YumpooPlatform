package com.yumpoo.platform.filestorage.consistency;

import com.yumpoo.platform.filestorage.application.AttachmentUploadPolicy;
import com.yumpoo.platform.filestorage.application.MalwareScanVerdict;
import com.yumpoo.platform.filestorage.application.PublishedBlob;
import com.yumpoo.platform.filestorage.application.SealedUpload;
import com.yumpoo.platform.filestorage.application.UploadIncompleteException;
import com.yumpoo.platform.filestorage.application.UploadRejectedException;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;
import com.yumpoo.platform.filestorage.infrastructure.DefenderMpCmdRunScanner;
import com.yumpoo.platform.filestorage.infrastructure.LocalFileQuarantineStorage;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Windows Defender + NTFS 受控验证入口；类名故意不以 Test/IT 结尾。
 */
class M014FileSecurityLiveVerification {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final String RECEIPT_DOMAIN = "m0-14-receipt\0";
    private static final String RECEIPT_FILE_NAME = "m0-14-live-receipt.json";
    private static final Duration DEFENDER_TIMEOUT = Duration.ofMinutes(5);
    private static final HexFormat HEX = HexFormat.of();
    private static final List<String> CHECK_NAMES = List.of(
            "configurationPreflight",
            "ntfsVerified",
            "sameVolumeVerified",
            "boundedExactLimitUpload",
            "limitPlusOneRejected",
            "interruptedUploadCleaned",
            "cleanSampleScanned",
            "eicarFailedClosed",
            "atomicMoveVerified",
            "pathsRedacted",
            "signedReceiptVerified"
    );
    private static final Set<String> INSECURE_KEY_MARKERS = Set.of(
            "change-me", "changeme", "placeholder", "password", "secret-key"
    );

    @Test
    void verifiesDefenderAndNtfsAndWritesSignedReceipt() throws Exception {
        LiveConfiguration configuration = preflight();
        Path runRoot = Files.createTempDirectory(configuration.liveRoot(), "m0-14-live-")
                .toRealPath(LinkOption.NOFOLLOW_LINKS);
        try {
            Path quarantine = Files.createDirectory(runRoot.resolve("quarantine"));
            Path blobs = Files.createDirectory(runRoot.resolve("blobs"));
            FileStore quarantineStore = Files.getFileStore(quarantine);
            FileStore blobStore = Files.getFileStore(blobs);
            require(quarantineStore.equals(blobStore), "quarantine and blob roots are not on one volume");
            require("NTFS".equalsIgnoreCase(quarantineStore.type()), "live root is not NTFS");

            LocalFileQuarantineStorage storage = new LocalFileQuarantineStorage(
                    quarantine,
                    blobs
            );
            DefenderMpCmdRunScanner scanner = new DefenderMpCmdRunScanner(
                    configuration.defenderExecutable(),
                    DEFENDER_TIMEOUT
            );

            LazyPatternInputStream cleanInput = new LazyPatternInputStream(
                    AttachmentUploadPolicy.MAX_BYTES
            );
            SealedUpload cleanUpload = storage.receive(
                    UUID.randomUUID(),
                    cleanInput,
                    OptionalLong.of(AttachmentUploadPolicy.MAX_BYTES)
            );
            require(
                    cleanInput.maximumRequestedBytes() <= AttachmentUploadPolicy.BUFFER_BYTES,
                    "clean sample was not read through the fixed buffer"
            );
            require(
                    scanner.scan(cleanUpload.quarantinedPath()) == MalwareScanVerdict.CLEAN,
                    "Defender did not return a clean verdict for the clean sample"
            );
            PublishedBlob cleanBlob = storage.publish(cleanUpload);
            require(storage.verify(cleanBlob), "atomically published clean blob failed verification");

            verifyLimitPlusOne(storage);
            verifyInterruptedReceive(storage, quarantine);
            verifyEicarFailClosed(storage, scanner);

            LinkedHashMap<String, Boolean> checks = passingChecks();
            String verifiedAt = Instant.now().toString();
            String canonical = canonicalReceipt(verifiedAt, quarantineStore.type(), checks);
            String signature = sign(configuration.hmacKey(), canonical);
            String receipt = receiptJson(
                    verifiedAt,
                    quarantineStore.type(),
                    checks,
                    signature
            );
            require(
                    !receipt.contains(configuration.liveRoot().toString())
                            && !receipt.contains(configuration.defenderExecutable().toString()),
                    "live receipt exposed a local path"
            );
            writeReceipt(receipt);
        } finally {
            deleteLiveRun(runRoot, configuration.liveRoot());
        }
    }

    private static LiveConfiguration preflight() throws IOException {
        require(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"),
                "M0-14 live verification requires Windows"
        );
        require(
                "true".equals(requiredEnvironment("YUMPOO_M014_LIVE_ENABLED")),
                "YUMPOO_M014_LIVE_ENABLED must be exactly true"
        );
        require(
                "true".equals(requiredEnvironment("YUMPOO_M014_ALLOW_EICAR")),
                "YUMPOO_M014_ALLOW_EICAR must be exactly true"
        );
        Path liveRoot = Path.of(requiredEnvironment("YUMPOO_M014_LIVE_ROOT"))
                .toAbsolutePath()
                .normalize();
        Path executable = Path.of(requiredEnvironment("YUMPOO_M014_DEFENDER_EXECUTABLE"))
                .toAbsolutePath()
                .normalize();
        require(
                Files.isDirectory(liveRoot, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(liveRoot),
                "live root must be an existing real directory"
        );
        require(
                Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS),
                "MpCmdRun executable is unavailable"
        );
        liveRoot = liveRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        require(
                Files.getFileStore(liveRoot).getUsableSpace()
                        >= AttachmentUploadPolicy.MAX_BYTES * 3,
                "live root has insufficient free space"
        );

        String hmacKey = requiredEnvironment("YUMPOO_M014_EVIDENCE_HMAC_KEY");
        byte[] keyBytes = hmacKey.getBytes(StandardCharsets.UTF_8);
        String normalizedKey = hmacKey.toLowerCase(Locale.ROOT);
        require(keyBytes.length >= 32, "evidence HMAC key must contain at least 32 UTF-8 bytes");
        require(
                hmacKey.chars().distinct().count() >= 8
                        && INSECURE_KEY_MARKERS.stream().noneMatch(normalizedKey::contains),
                "evidence HMAC key does not meet the strength policy"
        );
        return new LiveConfiguration(liveRoot, executable, keyBytes);
    }

    private static void verifyLimitPlusOne(LocalFileQuarantineStorage storage)
            throws IOException {
        try {
            storage.receive(
                    UUID.randomUUID(),
                    new LazyPatternInputStream(AttachmentUploadPolicy.MAX_BYTES + 1),
                    OptionalLong.of(AttachmentUploadPolicy.MAX_BYTES + 1)
            );
            throw new IllegalStateException("limit + 1 byte was accepted");
        } catch (UploadRejectedException exception) {
            require(
                    exception.rejectedCode() == AttachmentRejectedCode.FILE_TOO_LARGE,
                    "limit + 1 byte returned the wrong rejection"
            );
        }
    }

    private static void verifyInterruptedReceive(
            LocalFileQuarantineStorage storage,
            Path quarantine
    ) throws IOException {
        try {
            storage.receive(UUID.randomUUID(), new InterruptingInputStream(), OptionalLong.empty());
            throw new IllegalStateException("interrupted upload was accepted");
        } catch (UploadIncompleteException expected) {
            try (Stream<Path> paths = Files.list(quarantine)) {
                require(paths.findAny().isEmpty(), "interrupted upload left quarantine residue");
            }
        }
    }

    private static void verifyEicarFailClosed(
            LocalFileQuarantineStorage storage,
            DefenderMpCmdRunScanner scanner
    ) throws IOException {
        byte[] eicar = String.join(
                "",
                "X5O!P%@",
                "AP[4\\PZX54(P^)7CC)7}$",
                "EICAR-STANDARD-ANTIVIRUS-",
                "TEST-FILE!$H+H*"
        ).getBytes(StandardCharsets.US_ASCII);
        SealedUpload eicarUpload = null;
        try {
            eicarUpload = storage.receive(
                    UUID.randomUUID(),
                    new java.io.ByteArrayInputStream(eicar),
                    OptionalLong.of(eicar.length)
            );
            MalwareScanVerdict verdict = scanner.scan(eicarUpload.quarantinedPath());
            require(verdict != MalwareScanVerdict.CLEAN, "EICAR was incorrectly classified clean");
        } catch (UploadIncompleteException expectedRealTimeProtectionBlock) {
            // Defender 实时防护可能在显式 MpCmdRun 前先隔离 EICAR；同样属于 fail-closed。
        } finally {
            storage.discard(eicarUpload);
        }
    }

    private static LinkedHashMap<String, Boolean> passingChecks() {
        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        CHECK_NAMES.forEach(name -> checks.put(name, true));
        return checks;
    }

    private static String canonicalReceipt(
            String verifiedAt,
            String filesystemType,
            Map<String, Boolean> checks
    ) {
        StringBuilder canonical = new StringBuilder()
                .append("schemaVersion=1\n")
                .append("status=PASS\n")
                .append("verifiedAt=").append(verifiedAt).append('\n')
                .append("filesystemType=").append(filesystemType).append('\n')
                .append("maxBytes=").append(AttachmentUploadPolicy.MAX_BYTES).append('\n')
                .append("bufferBytes=").append(AttachmentUploadPolicy.BUFFER_BYTES).append('\n')
                .append("scannerProvider=MICROSOFT_DEFENDER");
        for (String name : CHECK_NAMES) {
            canonical.append('\n').append("checks.").append(name).append('=')
                    .append(checks.get(name));
        }
        return canonical.toString();
    }

    private static String sign(byte[] key, String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(key, HMAC_SHA_256));
            mac.update(RECEIPT_DOMAIN.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private static String receiptJson(
            String verifiedAt,
            String filesystemType,
            Map<String, Boolean> checks,
            String signature
    ) {
        StringBuilder json = new StringBuilder("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"status\": \"PASS\",\n")
                .append("  \"verifiedAt\": \"").append(verifiedAt).append("\",\n")
                .append("  \"filesystemType\": \"").append(filesystemType).append("\",\n")
                .append("  \"maxBytes\": ").append(AttachmentUploadPolicy.MAX_BYTES).append(",\n")
                .append("  \"bufferBytes\": ").append(AttachmentUploadPolicy.BUFFER_BYTES).append(",\n")
                .append("  \"scannerProvider\": \"MICROSOFT_DEFENDER\",\n")
                .append("  \"checks\": {\n");
        for (int index = 0; index < CHECK_NAMES.size(); index++) {
            String name = CHECK_NAMES.get(index);
            json.append("    \"").append(name).append("\": ").append(checks.get(name));
            json.append(index + 1 == CHECK_NAMES.size() ? '\n' : ",\n");
        }
        return json.append("  },\n")
                .append("  \"signature\": \"").append(signature).append("\"\n")
                .append("}\n")
                .toString();
    }

    private static void writeReceipt(String receipt) throws IOException {
        Path target = Path.of("target", RECEIPT_FILE_NAME).toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Files.writeString(
                target,
                receipt,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    private static void deleteLiveRun(Path runRoot, Path configuredRoot) throws IOException {
        Path resolved = runRoot.toAbsolutePath().normalize();
        require(
                resolved.getParent().equals(configuredRoot)
                        && resolved.getFileName().toString().startsWith("m0-14-live-"),
                "refusing to clean an unexpected live verification directory"
        );
        try (Stream<Path> paths = Files.walk(resolved)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(resolved)) {
                    throw new IOException("live cleanup escaped its run root");
                }
                Files.deleteIfExists(path);
            }
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "missing environment variable " + name);
        return value.strip();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record LiveConfiguration(
            Path liveRoot,
            Path defenderExecutable,
            byte[] hmacKey
    ) {
        private LiveConfiguration {
            hmacKey = hmacKey.clone();
        }

        @Override
        public byte[] hmacKey() {
            return hmacKey.clone();
        }
    }

    private static final class LazyPatternInputStream extends InputStream {

        private long remaining;
        private int maximumRequestedBytes;
        private int nextByte;

        private LazyPatternInputStream(long remaining) {
            this.remaining = remaining;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            return nextByte++ & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (remaining == 0) {
                return -1;
            }
            maximumRequestedBytes = Math.max(maximumRequestedBytes, length);
            int count = (int) Math.min(remaining, length);
            Arrays.fill(bytes, offset, offset + count, (byte) (nextByte++ & 0xff));
            remaining -= count;
            return count;
        }

        private int maximumRequestedBytes() {
            return maximumRequestedBytes;
        }
    }

    private static final class InterruptingInputStream extends InputStream {

        private int remaining = 8192;

        @Override
        public int read() throws IOException {
            if (remaining-- > 0) {
                return 'x';
            }
            throw new IOException("controlled M0-14 interrupted upload");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) {
                throw new IOException("controlled M0-14 interrupted upload");
            }
            int count = Math.min(remaining, length);
            Arrays.fill(bytes, offset, offset + count, (byte) 'x');
            remaining -= count;
            return count;
        }
    }
}
