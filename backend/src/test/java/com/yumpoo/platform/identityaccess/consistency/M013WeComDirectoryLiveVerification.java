package com.yumpoo.platform.identityaccess.consistency;

import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberFingerprint;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySnapshotCollector;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySnapshotFailure;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySnapshotResult;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryGateway;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryGatewayException;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryPage;
import com.yumpoo.platform.identityaccess.infrastructure.wecom.RestClientWeComDirectoryGateway;
import com.yumpoo.platform.identityaccess.testing.M013DirectoryProbeRepository;
import com.yumpoo.platform.identityaccess.testing.M013DirectoryReconciliationService;
import com.yumpoo.platform.identityaccess.testing.M013OmittedCursorConfirmation;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * M0-13 受控真实验证入口。类名故意不以 Test/IT 结尾，普通 Maven 验证不会发现它。
 */
@Import({
        PostgreSqlTestContainerConfiguration.class,
        M013DirectoryProbeRepository.class,
        M013DirectoryReconciliationService.class
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=false",
                "yumpoo.outbox.enabled=false"
        }
)
@Sql(
        scripts = "/sql/m0-13-probe-schema.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
@Sql(
        scripts = "/sql/m0-13-probe-drop.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS
)
class M013WeComDirectoryLiveVerification {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final int LIVE_PAGE_SIZE = 1;
    private static final int WIDE_PAGE_SIZE = 10_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int RECEIPT_SCHEMA_VERSION = 1;
    private static final String RECEIPT_STATUS = "PASS";
    private static final String RECEIPT_DOMAIN = "receipt\0";
    private static final String RECEIPT_FILE_NAME = "m0-13-live-receipt.json";
    private static final String EXPECTED_SIGNING_VECTOR =
            "3eb43ef6ad4b3701555abdd7e5d9fba4eb1ea660394d4d78821f7af860cb8965";
    private static final Pattern LOWERCASE_SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final HexFormat HEX = HexFormat.of();
    private static final Set<String> INSECURE_KEY_MARKERS = Set.of(
            "change-me",
            "changeme",
            "placeholder",
            "password",
            "secret-key"
    );
    private static final List<String> CHECK_NAMES = List.of(
            "configurationPreflight",
            "realDirectoryRead",
            "providerPaginationObserved",
            "providerTerminalCursorOmissionConfirmed",
            "rerunIdempotent",
            "pageFailureSafe",
            "itemFailurePartialSafe",
            "syntheticDepartureDetected",
            "syntheticReturnReused",
            "secretsRedacted",
            "externalLimitsRecorded"
    );

    @Autowired
    private M013DirectoryProbeRepository repository;

    @Autowired
    private M013DirectoryReconciliationService reconciliationService;

    @Test
    void verifiesRealDirectoryAndWritesSignedReceipt() throws IOException {
        LiveConfiguration configuration = preflightConfiguration();
        verifyReceiptSigningCompatibility();

        Clock liveClock = Clock.systemUTC();
        WeComDirectoryGateway realGateway = new RestClientWeComDirectoryGateway(
                liveRestClientBuilder(),
                configuration.corpId(),
                configuration.directorySecret(),
                liveClock
        );
        DirectorySnapshotCollector narrowCollector = new DirectorySnapshotCollector(
                realGateway,
                configuration.corpId(),
                configuration.hmacKey(),
                LIVE_PAGE_SIZE
        );
        DirectorySnapshotCollector wideCollector = new DirectorySnapshotCollector(
                realGateway,
                configuration.corpId(),
                configuration.hmacKey(),
                WIDE_PAGE_SIZE
        );

        DirectorySnapshotResult firstSnapshot = narrowCollector.collect();
        DirectorySnapshotResult repeatedSnapshot = narrowCollector.collect();
        DirectorySnapshotResult wideSnapshot = wideCollector.collect();
        M013OmittedCursorConfirmation.ConfirmedSnapshot confirmedSnapshot =
                M013OmittedCursorConfirmation.confirm(
                        firstSnapshot,
                        repeatedSnapshot,
                        wideSnapshot
        );
        Set<String> realFingerprints = confirmedSnapshot.memberFingerprints();

        M013DirectoryProbeRepository.RunRow initialRun = reconciliationService.synchronize(
                realFingerprints,
                true,
                Set.of()
        );
        requireSuccessfulInitialRun(initialRun, realFingerprints.size());
        Map<String, M013DirectoryProbeRepository.MemberRow> initialMembers =
                memberRows(realFingerprints);

        M013DirectoryProbeRepository.RunRow replayRun = reconciliationService.synchronize(
                realFingerprints,
                true,
                Set.of()
        );
        requireIdempotentReplay(replayRun, initialRun, initialMembers, realFingerprints);

        verifyPageFailureSafety(
                realGateway,
                configuration,
                realFingerprints
        );

        String departureTarget = realFingerprints.stream().sorted().findFirst().orElseThrow();
        M013DirectoryProbeRepository.MemberRow targetBeforeFailure = member(departureTarget);
        verifyItemFailureSafety(
                realFingerprints,
                departureTarget,
                targetBeforeFailure
        );
        M013DirectoryProbeRepository.MemberRow recoveredTarget = verifyRecoveryRerun(
                realFingerprints,
                initialMembers,
                initialRun,
                departureTarget
        );
        verifyDepartureAndReturn(
                realFingerprints,
                departureTarget,
                recoveredTarget
        );
        verifyExternalLimits();

        LinkedHashMap<String, Boolean> checks = passingChecks();
        String verifiedAt = liveClock.instant().toString();
        String canonical = canonicalReceipt(
                verifiedAt,
                confirmedSnapshot.corpFingerprint(),
                confirmedSnapshot.snapshotFingerprint(),
                checks
        );
        String signature = signReceipt(configuration.hmacKey(), canonical);
        String receipt = receiptJson(
                verifiedAt,
                confirmedSnapshot.corpFingerprint(),
                confirmedSnapshot.snapshotFingerprint(),
                checks,
                signature
        );
        verifySecretsRedacted(
                configuration,
                canonical,
                receipt,
                firstSnapshot,
                repeatedSnapshot,
                wideSnapshot
        );
        writeReceiptCreateNew(receipt);
    }

    private static RestClient.Builder liveRestClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory);
    }

    private void requireSuccessfulInitialRun(
            M013DirectoryProbeRepository.RunRow run,
            int memberCount
    ) {
        require(
                "SUCCEEDED".equals(run.status())
                        && run.scanComplete()
                        && run.discoveredCount() == memberCount
                        && run.createdCount() == memberCount
                        && run.unchangedCount() == 0
                        && run.returnedCount() == 0
                        && run.leftCount() == 0
                        && run.failedCount() == 0
                        && repository.memberCount() == memberCount,
                "M0-13 initial reconciliation did not persist a complete snapshot"
        );
    }

    private void requireIdempotentReplay(
            M013DirectoryProbeRepository.RunRow replayRun,
            M013DirectoryProbeRepository.RunRow initialRun,
            Map<String, M013DirectoryProbeRepository.MemberRow> initialMembers,
            Set<String> fingerprints
    ) {
        require(
                "SUCCEEDED".equals(replayRun.status())
                        && replayRun.scanComplete()
                        && replayRun.createdCount() == 0
                        && replayRun.unchangedCount() == fingerprints.size()
                        && replayRun.returnedCount() == 0
                        && replayRun.leftCount() == 0
                        && replayRun.failedCount() == 0
                        && repository.memberCount() == fingerprints.size(),
                "M0-13 replay was not idempotent"
        );
        for (String fingerprint : fingerprints) {
            M013DirectoryProbeRepository.MemberRow before = initialMembers.get(fingerprint);
            M013DirectoryProbeRepository.MemberRow after = member(fingerprint);
            require(
                    before != null
                            && before.id().equals(after.id())
                            && before.firstSeenRunId().equals(initialRun.id())
                            && after.firstSeenRunId().equals(initialRun.id())
                            && after.lastSeenRunId().equals(replayRun.id())
                            && "ACTIVE".equals(after.employmentStatus()),
                    "M0-13 replay changed stable member identity"
            );
        }
    }

    private void verifyPageFailureSafety(
            WeComDirectoryGateway realGateway,
            LiveConfiguration configuration,
            Set<String> realFingerprints
    ) {
        Map<String, M013DirectoryProbeRepository.MemberRow> beforeFailure =
                memberRows(realFingerprints);
        DirectorySnapshotCollector failureCollector = new DirectorySnapshotCollector(
                new SecondPageFailureGateway(realGateway),
                configuration.corpId(),
                configuration.hmacKey(),
                LIVE_PAGE_SIZE
        );
        DirectorySnapshotResult.Incomplete incomplete = requireIncomplete(
                failureCollector.collect(),
                "M0-13 controlled second-page failure was not fail closed"
        );
        require(
                incomplete.failure() == DirectorySnapshotFailure.TRANSPORT_ERROR
                        && incomplete.completedPageCount() == 1
                        && incomplete.memberCount() > 0,
                "M0-13 controlled page failure had an unexpected safe classification"
        );

        M013DirectoryProbeRepository.RunRow failedRun = reconciliationService.synchronize(
                fingerprintValues(incomplete),
                false,
                Set.of()
        );
        require(
                "FAILED".equals(failedRun.status())
                        && !failedRun.scanComplete()
                        && failedRun.discoveredCount() == incomplete.memberCount()
                        && failedRun.createdCount() == 0
                        && failedRun.unchangedCount() == 0
                        && failedRun.returnedCount() == 0
                        && failedRun.leftCount() == 0
                        && failedRun.failedCount() == 0
                        && repository.seenCount(failedRun.id()) == incomplete.memberCount()
                        && repository.memberCount() == realFingerprints.size()
                        && beforeFailure.equals(memberRows(realFingerprints)),
                "M0-13 incomplete page scan changed reconciled member state"
        );
    }

    private void verifyItemFailureSafety(
            Set<String> realFingerprints,
            String failedExistingFingerprint,
            M013DirectoryProbeRepository.MemberRow beforeFailure
    ) {
        M013DirectoryProbeRepository.RunRow partialRun = reconciliationService.synchronize(
                realFingerprints,
                true,
                Set.of(failedExistingFingerprint)
        );
        M013DirectoryProbeRepository.MemberRow afterFailure = member(failedExistingFingerprint);
        require(
                "PARTIALLY_SUCCEEDED".equals(partialRun.status())
                        && partialRun.scanComplete()
                        && partialRun.discoveredCount() == realFingerprints.size()
                        && partialRun.createdCount() == 0
                        && partialRun.unchangedCount() == realFingerprints.size() - 1
                        && partialRun.returnedCount() == 0
                        && partialRun.leftCount() == 0
                        && partialRun.failedCount() == 1
                        && repository.memberCount() == realFingerprints.size()
                        && beforeFailure.equals(afterFailure),
                "M0-13 item failure was not isolated as a partial result"
        );
        require(
                repository.findSeen(partialRun.id(), failedExistingFingerprint)
                        .map(seen -> "FAILED".equals(seen.result())
                                && seen.memberId() == null
                                && M013DirectoryReconciliationService.ITEM_FAILURE_CODE
                                .equals(seen.errorCode()))
                        .orElse(false),
                "M0-13 item failure did not retain a safe failure marker"
        );
    }

    private M013DirectoryProbeRepository.MemberRow verifyRecoveryRerun(
            Set<String> realFingerprints,
            Map<String, M013DirectoryProbeRepository.MemberRow> initialMembers,
            M013DirectoryProbeRepository.RunRow initialRun,
            String departureTarget
    ) {
        M013DirectoryProbeRepository.RunRow recoveryRun = reconciliationService.synchronize(
                realFingerprints,
                true,
                Set.of()
        );
        require(
                "SUCCEEDED".equals(recoveryRun.status())
                        && recoveryRun.scanComplete()
                        && recoveryRun.discoveredCount() == realFingerprints.size()
                        && recoveryRun.createdCount() == 0
                        && recoveryRun.unchangedCount() == realFingerprints.size()
                        && recoveryRun.returnedCount() == 0
                        && recoveryRun.leftCount() == 0
                        && recoveryRun.failedCount() == 0
                        && repository.memberCount() == realFingerprints.size(),
                "M0-13 recovery rerun was not idempotent"
        );
        for (String fingerprint : realFingerprints) {
            M013DirectoryProbeRepository.MemberRow initial = initialMembers.get(fingerprint);
            M013DirectoryProbeRepository.MemberRow recovered = member(fingerprint);
            require(
                    initial != null
                            && recovered.id().equals(initial.id())
                            && recovered.firstSeenRunId().equals(initialRun.id())
                            && recovered.lastSeenRunId().equals(recoveryRun.id())
                            && "ACTIVE".equals(recovered.employmentStatus()),
                    "M0-13 recovery rerun changed stable member identity"
            );
        }
        return member(departureTarget);
    }

    private void verifyDepartureAndReturn(
            Set<String> realFingerprints,
            String departureTarget,
            M013DirectoryProbeRepository.MemberRow stableIdentity
    ) {
        Set<String> afterDeparture = new LinkedHashSet<>(realFingerprints);
        require(afterDeparture.remove(departureTarget), "M0-13 departure target was unavailable");

        M013DirectoryProbeRepository.RunRow departureRun = reconciliationService.synchronize(
                afterDeparture,
                true,
                Set.of()
        );
        M013DirectoryProbeRepository.MemberRow left = member(departureTarget);
        require(
                "SUCCEEDED".equals(departureRun.status())
                        && departureRun.scanComplete()
                        && departureRun.discoveredCount() == realFingerprints.size() - 1
                        && departureRun.createdCount() == 0
                        && departureRun.unchangedCount() == realFingerprints.size() - 1
                        && departureRun.returnedCount() == 0
                        && departureRun.leftCount() == 1
                        && departureRun.failedCount() == 0
                        && left.id().equals(stableIdentity.id())
                        && left.firstSeenRunId().equals(stableIdentity.firstSeenRunId())
                        && "LEFT".equals(left.employmentStatus()),
                "M0-13 synthetic departure did not preserve stable member identity"
        );

        M013DirectoryProbeRepository.RunRow returnRun = reconciliationService.synchronize(
                realFingerprints,
                true,
                Set.of()
        );
        M013DirectoryProbeRepository.MemberRow returned = member(departureTarget);
        require(
                "SUCCEEDED".equals(returnRun.status())
                        && returnRun.scanComplete()
                        && returnRun.discoveredCount() == realFingerprints.size()
                        && returnRun.createdCount() == 0
                        && returnRun.unchangedCount() == realFingerprints.size() - 1
                        && returnRun.returnedCount() == 1
                        && returnRun.leftCount() == 0
                        && returnRun.failedCount() == 0
                        && repository.memberCount() == realFingerprints.size()
                        && returned.id().equals(stableIdentity.id())
                        && returned.firstSeenRunId().equals(stableIdentity.firstSeenRunId())
                        && returned.lastSeenRunId().equals(returnRun.id())
                        && "ACTIVE".equals(returned.employmentStatus()),
                "M0-13 synthetic return did not reuse stable member identity"
        );
    }

    private static void verifyExternalLimits() {
        require(
                DirectorySnapshotCollector.MIN_PAGE_SIZE == 1
                        && DirectorySnapshotCollector.MAX_PAGE_SIZE == 10_000
                        && DirectorySnapshotCollector.MAX_PAGE_COUNT == 10_000
                        && WIDE_PAGE_SIZE == DirectorySnapshotCollector.MAX_PAGE_SIZE
                        && DirectorySnapshotFailure.SYSTEM_BUSY.retryable()
                        && DirectorySnapshotFailure.RATE_LIMITED.retryable()
                        && !DirectorySnapshotFailure.INVALID_CREDENTIALS.retryable()
                        && !DirectorySnapshotFailure.ACCESS_TOKEN_REJECTED.retryable()
                        && !DirectorySnapshotFailure.PERMISSION_DENIED.retryable()
                        && !DirectorySnapshotFailure.UNTRUSTED_IP.retryable()
                        && !DirectorySnapshotFailure.MISSING_CURSOR.retryable(),
                "M0-13 external directory limits or retry classifications changed"
        );
    }

    private Map<String, M013DirectoryProbeRepository.MemberRow> memberRows(
            Set<String> fingerprints
    ) {
        Map<String, M013DirectoryProbeRepository.MemberRow> rows = new LinkedHashMap<>();
        for (String fingerprint : fingerprints.stream().sorted().toList()) {
            rows.put(fingerprint, member(fingerprint));
        }
        return Map.copyOf(rows);
    }

    private M013DirectoryProbeRepository.MemberRow member(String fingerprint) {
        return repository.findMember(fingerprint).orElseThrow(() ->
                new IllegalStateException("M0-13 expected member state was unavailable")
        );
    }

    private static Set<String> fingerprintValues(DirectorySnapshotResult snapshot) {
        Set<String> values = new LinkedHashSet<>();
        for (DirectoryMemberFingerprint fingerprint : snapshot.memberFingerprints()) {
            values.add(fingerprint.value());
        }
        require(
                values.size() == snapshot.memberCount(),
                "M0-13 directory snapshot contained duplicate fingerprints"
        );
        return Set.copyOf(values);
    }

    private static DirectorySnapshotResult.Incomplete requireIncomplete(
            DirectorySnapshotResult result,
            String safeMessage
    ) {
        if (result instanceof DirectorySnapshotResult.Incomplete incomplete) {
            return incomplete;
        }
        throw new IllegalStateException(safeMessage);
    }

    private static LiveConfiguration preflightConfiguration() {
        String profiles = requiredEnvironment("SPRING_PROFILES_ACTIVE");
        String enabled = requiredEnvironment("YUMPOO_M013_WECOM_ENABLED");
        String corpId = requiredEnvironment("YUMPOO_M013_WECOM_CORP_ID");
        String directorySecret = requiredEnvironment(
                "YUMPOO_M013_WECOM_DIRECTORY_SECRET"
        );
        String hmacKey = requiredEnvironment("YUMPOO_M013_EVIDENCE_HMAC_KEY");

        boolean liveProfileEnabled = List.of(profiles.split(",", -1)).stream()
                .map(String::trim)
                .anyMatch("m0-13-live"::equals);
        require(liveProfileEnabled, "M0-13 live profile is not enabled");
        require("true".equals(enabled), "M0-13 live verification is not enabled");

        byte[] hmacKeyBytes = hmacKey.getBytes(StandardCharsets.UTF_8);
        String normalizedKey = hmacKey.toLowerCase(Locale.ROOT);
        long distinctCodePoints = hmacKey.codePoints().distinct().limit(8).count();
        boolean insecureMarker = INSECURE_KEY_MARKERS.stream()
                .anyMatch(normalizedKey::contains);
        require(
                hmacKeyBytes.length >= 32
                        && distinctCodePoints >= 8
                        && !insecureMarker,
                "M0-13 evidence HMAC key does not meet the strength policy"
        );
        require(
                !MessageDigest.isEqual(
                        hmacKeyBytes,
                        directorySecret.getBytes(StandardCharsets.UTF_8)
                ),
                "M0-13 evidence HMAC key must be independent from the directory secret"
        );
        return new LiveConfiguration(corpId, directorySecret, hmacKey);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "M0-13 required environment is missing: " + name);
        return value;
    }

    private static LinkedHashMap<String, Boolean> passingChecks() {
        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        for (String name : CHECK_NAMES) {
            checks.put(name, true);
        }
        return checks;
    }

    private static String canonicalReceipt(
            String verifiedAt,
            String corpFingerprint,
            String snapshotFingerprint,
            Map<String, Boolean> checks
    ) {
        require(
                CHECK_NAMES.equals(new ArrayList<>(checks.keySet()))
                        && checks.values().stream().allMatch(Boolean.TRUE::equals),
                "M0-13 receipt checks are incomplete"
        );
        List<String> lines = new ArrayList<>();
        lines.add("schemaVersion=" + RECEIPT_SCHEMA_VERSION);
        lines.add("status=" + RECEIPT_STATUS);
        lines.add("verifiedAt=" + verifiedAt);
        lines.add("corpFingerprint=" + corpFingerprint);
        lines.add("snapshotFingerprint=" + snapshotFingerprint);
        for (String name : CHECK_NAMES) {
            lines.add("checks." + name + "=true");
        }
        return String.join("\n", lines);
    }

    private static String signReceipt(String hmacKey, String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(
                    hmacKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA_256
            ));
            mac.update(RECEIPT_DOMAIN.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("M0-13 HMAC-SHA256 is unavailable");
        }
    }

    private static void verifyReceiptSigningCompatibility() {
        String canonical = canonicalReceipt(
                "2026-08-10T00:00:00.000Z",
                "a".repeat(64),
                "b".repeat(64),
                passingChecks()
        );
        require(
                EXPECTED_SIGNING_VECTOR.equals(signReceipt(
                        "M013-Test-Key-0123456789-abcdef!@",
                        canonical
                )),
                "M0-13 receipt signing compatibility self-check failed"
        );
    }

    private static String receiptJson(
            String verifiedAt,
            String corpFingerprint,
            String snapshotFingerprint,
            Map<String, Boolean> checks,
            String signature
    ) {
        require(
                LOWERCASE_SHA_256_HEX.matcher(corpFingerprint).matches()
                        && LOWERCASE_SHA_256_HEX.matcher(snapshotFingerprint).matches()
                        && LOWERCASE_SHA_256_HEX.matcher(signature).matches(),
                "M0-13 receipt fingerprint format is invalid"
        );
        require(
                CHECK_NAMES.equals(new ArrayList<>(checks.keySet()))
                        && checks.values().stream().allMatch(Boolean.TRUE::equals),
                "M0-13 receipt checks are invalid"
        );

        StringBuilder json = new StringBuilder(1024);
        json.append("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"status\": \"PASS\",\n")
                .append("  \"verifiedAt\": \"").append(verifiedAt).append("\",\n")
                .append("  \"corpFingerprint\": \"").append(corpFingerprint).append("\",\n")
                .append("  \"snapshotFingerprint\": \"")
                .append(snapshotFingerprint).append("\",\n")
                .append("  \"checks\": {\n");
        for (int index = 0; index < CHECK_NAMES.size(); index++) {
            String name = CHECK_NAMES.get(index);
            json.append("    \"").append(name).append("\": true");
            json.append(index + 1 == CHECK_NAMES.size() ? "\n" : ",\n");
        }
        json.append("  },\n")
                .append("  \"signature\": \"").append(signature).append("\"\n")
                .append("}\n");
        return json.toString();
    }

    private static void verifySecretsRedacted(
            LiveConfiguration configuration,
            String canonical,
            String receipt,
            DirectorySnapshotResult firstSnapshot,
            DirectorySnapshotResult repeatedSnapshot,
            DirectorySnapshotResult wideSnapshot
    ) {
        String rawMemberSentinel = "m013-raw-member-sentinel";
        String rawCursorSentinel = "m013-raw-cursor-sentinel";
        String diagnosticSurface = firstSnapshot
                + "|" + repeatedSnapshot
                + "|" + wideSnapshot
                + "|" + firstSnapshot.memberFingerprints().getFirst()
                + "|" + WeComDirectoryPage.next(
                List.of(rawMemberSentinel),
                rawCursorSentinel
        );
        List<String> sensitiveValues = List.of(
                configuration.corpId(),
                configuration.directorySecret(),
                configuration.hmacKey(),
                rawMemberSentinel,
                rawCursorSentinel
        );
        for (String sensitiveValue : sensitiveValues) {
            require(
                    !canonical.contains(sensitiveValue)
                            && !receipt.contains(sensitiveValue)
                            && !diagnosticSurface.contains(sensitiveValue),
                    "M0-13 receipt or diagnostic surface contains sensitive material"
            );
        }
    }

    private static void writeReceiptCreateNew(String receipt) throws IOException {
        Path targetDirectory = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(targetDirectory);
        Path receiptPath = targetDirectory.resolve(RECEIPT_FILE_NAME);
        Files.writeString(
                receiptPath,
                receipt,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    private static void require(boolean condition, String safeMessage) {
        if (!condition) {
            throw new IllegalStateException(safeMessage);
        }
    }

    private record LiveConfiguration(
            String corpId,
            String directorySecret,
            String hmacKey
    ) {

        @Override
        public String toString() {
            return "LiveConfiguration[REDACTED]";
        }
    }

    private static final class SecondPageFailureGateway implements WeComDirectoryGateway {

        private final WeComDirectoryGateway delegate;
        private int callCount;

        private SecondPageFailureGateway(WeComDirectoryGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public WeComDirectoryPage fetchPage(String cursor, int limit) {
            callCount++;
            if (callCount == 2) {
                throw new WeComDirectoryGatewayException(
                        DirectorySnapshotFailure.TRANSPORT_ERROR
                );
            }
            return delegate.fetchPage(cursor, limit);
        }
    }
}
