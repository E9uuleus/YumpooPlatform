package com.yumpoo.platform.identityaccess.api;

import java.util.Set;

/**
 * Login entry points reached before any session exists. The security chain permits them and the session
 * filter skips them; both read this one list so a new entry point cannot be permitted yet still rejected.
 */
final class AnonymousAuthenticationPaths {

    static final Set<String> ALL = Set.of(
            WebAuthenticationPaths.AUTHORIZE,
            WebAuthenticationPaths.CALLBACK,
            DesktopAuthenticationPaths.ATTEMPTS,
            DesktopAuthenticationPaths.CALLBACK,
            DesktopAuthenticationPaths.EXCHANGE
    );

    private AnonymousAuthenticationPaths() {
    }
}
