package com.yumpoo.platform.identityaccess.application.verification;

import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningService;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryOptionalField;
import com.yumpoo.platform.identityaccess.application.directory.WeComMemberProfile;
import com.yumpoo.platform.identityaccess.domain.identity.ProfileHash;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Service
public class IdentityAcceptanceFixtureProvisioner {

    private final DirectoryMemberProvisioningService provisioningService;

    public IdentityAcceptanceFixtureProvisioner(
            DirectoryMemberProvisioningService provisioningService
    ) {
        this.provisioningService = Objects.requireNonNull(
                provisioningService,
                "provisioningService must not be null"
        );
    }

    public DirectoryMemberProvisioningResult provision(String memberId, String displayName) {
        return provision(memberId, displayName, "M1-13 Verification");
    }

    public DirectoryMemberProvisioningResult provision(
            String memberId,
            String displayName,
            String departmentSummary
    ) {
        return provisioningService.provisionOrRefresh(new WeComMemberProfile(
                memberId,
                displayName,
                DirectoryOptionalField.unavailable(),
                DirectoryOptionalField.unavailable(),
                departmentSummary,
                new ProfileHash(sha256("profile:" + memberId))
        ));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
