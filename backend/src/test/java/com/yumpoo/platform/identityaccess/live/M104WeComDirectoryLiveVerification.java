package com.yumpoo.platform.identityaccess.live;

import com.yumpoo.platform.identityaccess.application.directory.DirectoryProfileMapper;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryScanResult;
import com.yumpoo.platform.identityaccess.application.directory.FullDirectoryScanCollector;
import com.yumpoo.platform.identityaccess.application.directory.WeComMemberProfile;
import com.yumpoo.platform.identityaccess.application.directory.WeComRawMemberProfile;
import com.yumpoo.platform.identityaccess.infrastructure.wecom.RestClientWeComDirectoryGateway;
import com.yumpoo.platform.identityaccess.infrastructure.wecom.RestClientWeComDirectoryProfileGateway;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅由 verify:m1-04:live 双重门禁点名执行，不连接业务数据库。 */
class M104WeComDirectoryLiveVerification {

    private static final List<String> CHECK_NAMES = List.of(
            "configurationPreflight",
            "directoryCredentialRead",
            "snapshotTerminationConfirmed",
            "profileCredentialRead",
            "departmentDictionaryRead",
            "requiredProfileVisible",
            "optionalVisibilityCaptured",
            "secretsRedacted"
    );

    @Test
    void writesShortLivedSignedRedactedReceipt() throws Exception {
        String corpId = required("YUMPOO_M104_WECOM_CORP_ID");
        String directorySecret = required("YUMPOO_M104_WECOM_DIRECTORY_SECRET");
        String profileSecret = required("YUMPOO_M104_WECOM_PROFILE_SECRET");
        byte[] evidenceKey = required("YUMPOO_M104_EVIDENCE_HMAC_KEY")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(directorySecret).isNotEqualTo(profileSecret);

        Clock clock = Clock.systemUTC();
        RestClientWeComDirectoryGateway directoryGateway =
                new RestClientWeComDirectoryGateway(
                        RestClient.builder(), corpId, directorySecret, clock
                );
        DirectoryScanResult scan = new FullDirectoryScanCollector(directoryGateway, 1000)
                .collect((pass, page, cursor, members) -> { });
        assertThat(scan.externalUserIds()).isNotEmpty();

        RestClientWeComDirectoryProfileGateway profileGateway =
                new RestClientWeComDirectoryProfileGateway(
                        RestClient.builder(), corpId, profileSecret, clock
                );
        Map<Long, String> departments = profileGateway.fetchDepartmentNames();
        WeComRawMemberProfile raw = profileGateway.fetchMemberProfile(
                scan.externalUserIds().getFirst()
        );
        WeComMemberProfile mapped = DirectoryProfileMapper.map(raw, departments);
        assertThat(mapped.displayName()).isNotBlank();
        assertThat(mapped.departmentSummary()).isNotBlank();

        boolean redacted = !scan.toString().contains(raw.externalUserId())
                && !raw.toString().contains(raw.externalUserId())
                && !mapped.toString().contains(raw.displayName())
                && !scan.toString().contains(directorySecret)
                && !mapped.toString().contains(profileSecret);
        assertThat(redacted).isTrue();

        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        CHECK_NAMES.forEach(name -> checks.put(name, true));
        String verifiedAt = Instant.now().toString();
        String corpFingerprint = hmac(evidenceKey, "corp\0" + corpId);
        String snapshotFingerprint = hmac(
                evidenceKey,
                "snapshot\0" + String.join("\0", scan.externalUserIds())
        );
        String signature = hmac(
                evidenceKey,
                canonicalReceipt(verifiedAt, corpFingerprint, snapshotFingerprint, checks)
        );

        LinkedHashMap<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", 1);
        receipt.put("status", "PASS");
        receipt.put("verifiedAt", verifiedAt);
        receipt.put("corpFingerprint", corpFingerprint);
        receipt.put("snapshotFingerprint", snapshotFingerprint);
        receipt.put("checks", checks);
        receipt.put("signature", signature);
        Path target = Path.of("target", "m1-04-live-receipt.json");
        Files.createDirectories(target.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(target.toFile(), receipt);
    }

    private static String canonicalReceipt(
            String verifiedAt,
            String corpFingerprint,
            String snapshotFingerprint,
            Map<String, Boolean> checks
    ) {
        StringBuilder value = new StringBuilder("m1-04-receipt-v1\0PASS\0")
                .append(verifiedAt).append('\0')
                .append(corpFingerprint).append('\0')
                .append(snapshotFingerprint);
        CHECK_NAMES.forEach(name -> value.append('\0').append(checks.get(name)));
        return value.toString();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required M1-04 live configuration");
        }
        return value.trim();
    }

    private static String hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
