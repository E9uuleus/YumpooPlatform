package com.yumpoo.platform.identityaccess.infrastructure.bootstrap;

import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapAuditService;
import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapInput;
import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapService;
import com.yumpoo.platform.identityaccess.infrastructure.wecom.DirectorySyncWeComProperties;
import com.yumpoo.platform.identityaccess.infrastructure.wecom.WebOAuthProperties;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.context.ConfigurableWebApplicationContext;

import java.net.URI;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InitialIdentityBootstrapRunnerTest {

    private static final UUID COMPANY_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Test
    void rejectsWebApplicationBeforeReadingSensitiveInput() {
        ConfigurableWebApplicationContext webContext = mock(ConfigurableWebApplicationContext.class);
        Environment environment = mock(Environment.class);
        when(environment.getProperty("spring.main.web-application-type", ""))
                .thenReturn("servlet");
        InitialIdentityBootstrapInputReader reader = mock(InitialIdentityBootstrapInputReader.class);
        InitialIdentityBootstrapService service = mock(InitialIdentityBootstrapService.class);
        InitialIdentityBootstrapAuditService audit = mock(InitialIdentityBootstrapAuditService.class);

        InitialIdentityBootstrapRunner runner = runner(
                webContext, environment, reader, service, audit,
                configuredOAuth("oauth-secret"),
                configuredDirectory("directory-secret", "profile-secret")
        );

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INITIAL_IDENTITY_BOOTSTRAP_WEB_MODE_FORBIDDEN");
        verify(reader, never()).read(any());
        verify(service, never()).execute(any(), any(), any());
    }

    @Test
    void rejectsSecretReuseWithoutDisclosingIdentityInput() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        Environment environment = mock(Environment.class);
        when(environment.getProperty("spring.main.web-application-type", ""))
                .thenReturn("none");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(environment.getProperty(
                "yumpoo.maintenance.app-manager.enabled", Boolean.class, false))
                .thenReturn(false);
        InitialIdentityBootstrapInputReader reader = mock(InitialIdentityBootstrapInputReader.class);
        when(reader.read(any())).thenReturn(new InitialIdentityBootstrapInput(
                "ww-test", "sensitive-app-manager-id", "sensitive-company-admin-id"));
        InitialIdentityBootstrapService service = mock(InitialIdentityBootstrapService.class);
        InitialIdentityBootstrapAuditService audit = mock(InitialIdentityBootstrapAuditService.class);

        InitialIdentityBootstrapRunner runner = runner(
                context, environment, reader, service, audit,
                configuredOAuth("shared-secret"),
                configuredDirectory("shared-secret", "profile-secret")
        );

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INITIAL_IDENTITY_BOOTSTRAP_WECOM_CONFIG_INVALID")
                .hasMessageNotContaining("sensitive-app-manager-id")
                .hasMessageNotContaining("sensitive-company-admin-id");
        verify(service, never()).execute(any(), any(), any());
        verify(audit).failed(
                eq(COMPANY_ID), any(), eq("approved change"), eq("CONFIGURATION"),
                eq("INITIAL_IDENTITY_BOOTSTRAP_WECOM_CONFIG_INVALID"), isNull());
    }

    private static InitialIdentityBootstrapRunner runner(
            ConfigurableApplicationContext context,
            Environment environment,
            InitialIdentityBootstrapInputReader reader,
            InitialIdentityBootstrapService service,
            InitialIdentityBootstrapAuditService audit,
            WebOAuthProperties oauth,
            DirectorySyncWeComProperties directory
    ) {
        CompanyConfigurationQuery companyQuery = mock(CompanyConfigurationQuery.class);
        when(companyQuery.current()).thenReturn(new CompanyConfigurationSnapshot(
                COMPANY_ID, "Yumpoo", ZoneId.of("Asia/Shanghai"),
                DayOfWeek.MONDAY, 480, 0));
        return new InitialIdentityBootstrapRunner(
                new InitialIdentityBootstrapRunnerProperties(
                        true, Path.of("C:/ProgramData/Yumpoo/secrets/bootstrap.json"),
                        "approved change"),
                reader,
                service,
                audit,
                companyQuery,
                oauth,
                directory,
                environment,
                context
        );
    }

    private static WebOAuthProperties configuredOAuth(String secret) {
        WebOAuthProperties properties = new WebOAuthProperties();
        properties.setEnabled(true);
        properties.setCorpId("ww-test");
        properties.setAgentId("1000009");
        properties.setAppSecret(secret);
        properties.setCallbackUri(URI.create(
                "https://wecom.example.test/api/v1/auth/wecom/callback"));
        properties.setElectronCallbackUri(URI.create(
                "https://wecom.example.test/api/v1/electron/auth/wecom/callback"));
        return properties;
    }

    private static DirectorySyncWeComProperties configuredDirectory(
            String directorySecret,
            String profileSecret
    ) {
        DirectorySyncWeComProperties properties = new DirectorySyncWeComProperties();
        properties.setEnabled(true);
        properties.setCorpId("ww-test");
        properties.setDirectorySecret(directorySecret);
        properties.setProfileSecret(profileSecret);
        return properties;
    }
}
