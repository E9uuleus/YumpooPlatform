package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.authentication.AuthenticationEventService;
import com.yumpoo.platform.identityaccess.application.authentication.WebAuthenticationService;
import com.yumpoo.platform.identityaccess.application.authentication.WebIdentityProvider;
import com.yumpoo.platform.identityaccess.application.authentication.WebLoginCompletionService;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHasher;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptStore;
import com.yumpoo.platform.identityaccess.application.oauth.SecureOAuthAttemptTokenGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        WebOAuthProperties.class,
        DirectorySyncWeComProperties.class,
        ControlledAuthenticationProperties.class
})
public class WebAuthenticationConfiguration {

    @Bean
    WebIdentityProvider webIdentityProvider(
            WebOAuthProperties oauth,
            ControlledAuthenticationProperties controlled,
            Environment environment,
            Clock clock
    ) {
        oauth.validateCleanup();
        controlled.validateForEnabled();
        if (oauth.isEnabled() && controlled.isEnabled()) {
            throw new IllegalStateException("Only one Web identity provider may be enabled");
        }
        if (controlled.isEnabled()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))
                    || !environment.acceptsProfiles(Profiles.of("local", "test"))) {
                throw new IllegalStateException(
                        "Controlled identity provider is restricted to local/test profiles"
                );
            }
            return new ControlledWebIdentityProvider(controlled, clock);
        }
        if (!oauth.isEnabled()) {
            return new DisabledWebIdentityProvider();
        }

        oauth.validateForEnabled();
        requireIndependentSecret(oauth, environment);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(oauth.getConnectTimeout());
        requestFactory.setReadTimeout(oauth.getReadTimeout());
        return new RestClientWebIdentityProvider(
                RestClient.builder().requestFactory(requestFactory),
                oauth,
                clock
        );
    }

    @Bean
    WebAuthenticationService webAuthenticationService(
            OAuthAttemptStore attemptStore,
            WebIdentityProvider identityProvider,
            WebLoginCompletionService completionService,
            AuthenticationEventService eventService,
            Clock clock
    ) {
        return new WebAuthenticationService(
                attemptStore,
                identityProvider,
                new SecureOAuthAttemptTokenGenerator(),
                new OAuthAttemptHasher(),
                completionService,
                eventService,
                clock
        );
    }

    private static void requireIndependentSecret(
            WebOAuthProperties oauth,
            Environment environment
    ) {
        String directorySecret = environment.getProperty(
                "yumpoo.wecom.directory.directory-secret"
        );
        String profileSecret = environment.getProperty(
                "yumpoo.wecom.directory.profile-secret"
        );
        if (sameNonBlank(oauth.getAppSecret(), directorySecret)
                || sameNonBlank(oauth.getAppSecret(), profileSecret)) {
            throw new IllegalStateException("WeCom OAuth app secret must be independent");
        }
    }

    private static boolean sameNonBlank(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }
}
