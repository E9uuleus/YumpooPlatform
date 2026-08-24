package com.yumpoo.platform.identityaccess.domain.identity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceSlugTest {

    private static final UUID USER_ID = UUID.fromString(
            "02e255e3-85b5-4d97-90ae-67b2c8bc6236"
    );

    @Test
    void normalizesExternalIdentityWithoutCouplingFutureChanges() {
        assertThat(WorkspaceSlug.fromExternalUserId("HanZhou.Jiang_Shang@Yu", USER_ID).value())
                .isEqualTo("hanzhou.jiang_shang@yu");
        assertThat(WorkspaceSlug.fromExternalUserId(" 研发/一组 ", USER_ID).value())
                .isEqualTo("u-02e255e385b54d9790ae67b2c8bc6236");
    }

    @Test
    void fallsBackForReservedValuesAndAddsStableCollisionSuffix() {
        WorkspaceSlug fallback = WorkspaceSlug.fromExternalUserId("admin", USER_ID);
        assertThat(fallback).isEqualTo(WorkspaceSlug.fallback(USER_ID));
        assertThat(new WorkspaceSlug("hanzhoujiangshangyu").disambiguated(USER_ID).value())
                .isEqualTo("hanzhoujiangshangyu-02e255e3");
    }

    @Test
    void rejectsNonCanonicalValues() {
        assertThatThrownBy(() -> new WorkspaceSlug("Uppercase"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkspaceSlug("settings"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
