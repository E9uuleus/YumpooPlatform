package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.authentication.WebIdentityProvider;
import com.yumpoo.platform.identityaccess.application.oauth.WeComAuthenticationFailedException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ControlledWebIdentityProvider implements WebIdentityProvider {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final int CODE_BYTES = 32;

    private final String corpId;
    private final String memberId;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> codes = new ConcurrentHashMap<>();

    ControlledWebIdentityProvider(
            ControlledAuthenticationProperties properties,
            Clock clock
    ) {
        properties.validateForEnabled();
        this.corpId = properties.getCorpId();
        this.memberId = properties.getMemberId();
        this.clock = clock;
    }

    @Override
    public String expectedCorpId() {
        return corpId;
    }

    @Override
    public URI buildAuthorizationUri(String state) {
        return buildAuthorizationUri(state, "/api/v1/auth/wecom/callback");
    }

    @Override
    public URI buildElectronAuthorizationUri(String state) {
        return buildAuthorizationUri(state, "/api/v1/electron/auth/wecom/callback");
    }

    private URI buildAuthorizationUri(String state, String callbackPath) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state must not be blank");
        }
        Instant now = clock.instant();
        codes.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
        String code;
        Instant expiresAt = now.plus(CODE_TTL);
        do {
            code = newCode();
        } while (codes.putIfAbsent(code, expiresAt) != null);
        return UriComponentsBuilder.fromPath(callbackPath)
                .queryParam("code", code)
                .queryParam("state", state)
                .build()
                .encode()
                .toUri();
    }

    @Override
    public WeComMemberIdentity exchangeCode(String code) {
        Instant expiresAt = code == null ? null : codes.remove(code);
        if (expiresAt == null || !clock.instant().isBefore(expiresAt)) {
            throw new WeComAuthenticationFailedException();
        }
        return new WeComMemberIdentity(corpId, memberId);
    }

    private String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
