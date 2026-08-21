package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import com.yumpoo.platform.identityaccess.application.authentication.WebLoginCompletionService;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.io.IOException;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(LocalAuthenticationProperties.class)
public class SessionSecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            SessionService sessionService,
            PlatformRoleQuery platformRoleQuery,
            ApiErrorWriter errorWriter,
            LocalAuthenticationProperties localAuthentication,
            WebLoginCompletionService webLoginCompletionService,
            Clock clock
    ) throws Exception {
        SessionBoundCsrfTokenRepository csrfRepository =
                new SessionBoundCsrfTokenRepository(sessionService, clock);
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        http
                .securityMatcher("/api/v1/**")
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.requireExplicitSave(true))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .requireCsrfProtectionMatcher(request ->
                                SessionRequestContext.present(request)
                                        && !isSafeMethod(request.getMethod()))
                        .csrfTokenRequestHandler(requestHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationRequired())
                        .accessDeniedHandler(csrfDenied(errorWriter)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                WebAuthenticationPaths.AUTHORIZE,
                                WebAuthenticationPaths.CALLBACK,
                                DesktopAuthenticationPaths.ATTEMPTS,
                                DesktopAuthenticationPaths.CALLBACK,
                                DesktopAuthenticationPaths.EXCHANGE
                        ).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new SessionAuthenticationFilter(
                                sessionService,
                                platformRoleQuery,
                                errorWriter,
                                new LocalSessionIssuer(
                                        localAuthentication,
                                        webLoginCompletionService
                                ),
                                clock
                        ),
                        CsrfFilter.class
                );
        return http.build();
    }

    private static AuthenticationEntryPoint authenticationRequired() {
        return (request, response, exception) -> {
            throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        };
    }

    private static AccessDeniedHandler csrfDenied(ApiErrorWriter writer) {
        return (request, response, exception) -> {
            if (exception instanceof MissingCsrfTokenException
                    || exception instanceof InvalidCsrfTokenException) {
                response.addHeader(
                        HttpHeaders.SET_COOKIE,
                        SessionHttpCookies.clearCsrf().toString()
                );
            }
            write(writer, request, response, StandardErrorCode.ACCESS_DENIED);
        };
    }

    private static boolean isSafeMethod(String method) {
        return "GET".equals(method)
                || "HEAD".equals(method)
                || "OPTIONS".equals(method)
                || "TRACE".equals(method);
    }

    private static void write(
            ApiErrorWriter writer,
            HttpServletRequest request,
            HttpServletResponse response,
            StandardErrorCode code
    ) throws IOException {
        Object requestId = request.getAttribute(RequestIdContext.ATTRIBUTE_NAME);
        writer.write(
                response,
                new ApplicationException(code),
                requestId == null ? "missing-request-id" : requestId.toString()
        );
    }
}
