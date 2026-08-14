package com.yumpoo.platform.foundation.application.authorization;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.domain.authorization.AuthorizationDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationGuardTest {

    @Test
    void allowContinues() {
        assertThatCode(() -> AuthorizationGuard.requireAllowed(AuthorizationDecision.ALLOW))
                .doesNotThrowAnyException();
    }

    @Test
    void visibleDenialMapsToAccessDenied() {
        assertThatThrownBy(() -> AuthorizationGuard.requireAllowed(AuthorizationDecision.DENY_VISIBLE))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(StandardErrorCode.ACCESS_DENIED));
    }

    @Test
    void hiddenDenialMapsToResourceNotFound() {
        assertThatThrownBy(() -> AuthorizationGuard.requireAllowed(AuthorizationDecision.DENY_HIDDEN))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND));
    }
}
