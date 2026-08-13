package com.yumpoo.platform.identityaccess.domain.identity;

import com.yumpoo.platform.identityaccess.application.directory.WeComMemberProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityModelTest {

    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test
    void normalizesWhitelistedProfileFieldsWithoutChangingIdentifierCase() {
        WeComMemberProfile profile = new WeComMemberProfile(
                " Member-A ",
                " Alice ",
                " alice@example.test ",
                " 13800000000 ",
                " Engineering ",
                new ProfileHash(HASH)
        );

        assertThat(profile.externalUserId()).isEqualTo("Member-A");
        assertThat(profile.displayName()).isEqualTo("Alice");
        assertThat(profile.email()).isEqualTo("alice@example.test");
        assertThat(profile.mobile()).isEqualTo("13800000000");
        assertThat(profile.departmentSummary()).isEqualTo("Engineering");
    }

    @Test
    void rejectsInvalidProfileBoundariesAndHashFormats() {
        assertThatThrownBy(() -> new ProfileHash("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WeComMemberProfile(
                "x".repeat(257),
                "Alice",
                null,
                null,
                null,
                new ProfileHash(HASH)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WeComMemberProfile(
                "member-a",
                " ",
                null,
                null,
                null,
                new ProfileHash(HASH)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sensitiveTypesHaveRedactedStringRepresentations() {
        UUID companyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = activeUser(userId, companyId);
        ExternalIdentity identity = new ExternalIdentity(
                UUID.randomUUID(),
                companyId,
                userId,
                ExternalIdentityProvider.WECOM,
                "secret-member-id",
                EmploymentStatus.ACTIVE,
                new ProfileHash(HASH),
                NOW,
                NOW,
                NOW
        );
        WeComMemberProfile profile = new WeComMemberProfile(
                "secret-member-id",
                "Sensitive Name",
                "sensitive@example.test",
                "13800000000",
                "Sensitive Department",
                new ProfileHash(HASH)
        );

        assertThat(user.toString())
                .contains("personalData=REDACTED")
                .doesNotContain("Sensitive Name", "sensitive@example.test");
        assertThat(identity.toString())
                .contains("identityData=REDACTED")
                .doesNotContain("secret-member-id", HASH);
        assertThat(profile.toString())
                .isEqualTo("WeComMemberProfile[REDACTED]");
        assertThat(profile.rawProfileHash().toString()).isEqualTo("ProfileHash[REDACTED]");
    }

    @Test
    void enforcesIndependentStatusFactCompleteness() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        assertThatThrownBy(() -> new User(
                userId,
                companyId,
                EmploymentStatus.LEFT,
                AccountStatus.ENABLED,
                "Alice",
                null,
                null,
                null,
                NOW,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                NOW,
                NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LEFT user");

        assertThatThrownBy(() -> new User(
                userId,
                companyId,
                EmploymentStatus.ACTIVE,
                AccountStatus.DISABLED,
                "Alice",
                null,
                null,
                null,
                NOW,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                NOW,
                NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISABLED user");
    }

    private static User activeUser(UUID userId, UUID companyId) {
        return new User(
                userId,
                companyId,
                EmploymentStatus.ACTIVE,
                AccountStatus.ENABLED,
                "Sensitive Name",
                "sensitive@example.test",
                "13800000000",
                "Sensitive Department",
                NOW,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                NOW,
                NOW
        );
    }
}
