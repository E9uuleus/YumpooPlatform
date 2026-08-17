package com.yumpoo.platform.identityaccess.api;

import java.net.URI;
import java.time.Instant;

public record DesktopAuthAttemptResponse(URI authorizationUrl, Instant expiresAt) {
}
