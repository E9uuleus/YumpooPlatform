package com.yumpoo.platform.identityaccess.infrastructure.bootstrap;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapAuditService;
import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapException;
import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapInput;
import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapResult;
import com.yumpoo.platform.identityaccess.application.bootstrap.InitialIdentityBootstrapService;
import com.yumpoo.platform.identityaccess.infrastructure.wecom.DirectorySyncWeComProperties;
import com.yumpoo.platform.identityaccess.infrastructure.wecom.WebOAuthProperties;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "yumpoo.maintenance.initial-identity",
        name = "enabled",
        havingValue = "true"
)
public class InitialIdentityBootstrapRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitialIdentityBootstrapRunner.class);

    private final InitialIdentityBootstrapRunnerProperties properties;
    private final InitialIdentityBootstrapInputReader inputReader;
    private final InitialIdentityBootstrapService bootstrapService;
    private final InitialIdentityBootstrapAuditService auditService;
    private final CompanyConfigurationQuery companyQuery;
    private final WebOAuthProperties oauth;
    private final DirectorySyncWeComProperties directory;
    private final Environment environment;
    private final ConfigurableApplicationContext applicationContext;

    public InitialIdentityBootstrapRunner(
            InitialIdentityBootstrapRunnerProperties properties,
            InitialIdentityBootstrapInputReader inputReader,
            InitialIdentityBootstrapService bootstrapService,
            InitialIdentityBootstrapAuditService auditService,
            CompanyConfigurationQuery companyQuery,
            WebOAuthProperties oauth,
            DirectorySyncWeComProperties directory,
            Environment environment,
            ConfigurableApplicationContext applicationContext
    ) {
        this.properties = properties;
        this.inputReader = inputReader;
        this.bootstrapService = bootstrapService;
        this.auditService = auditService;
        this.companyQuery = companyQuery;
        this.oauth = oauth;
        this.directory = directory;
        this.environment = environment;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        String requestId = "m115-bootstrap-" + UUID.randomUUID();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root(requestId))) {
            execute(requestId);
        }
    }

    private void execute(String requestId) {
        UUID companyId = companyQuery.current().companyId();
        String reason = "M1-15 initial identity bootstrap";
        try {
            reason = normalizedReason(properties.reasonReference());
            validateRuntimeBoundary();
            InitialIdentityBootstrapInput input = inputReader.read(properties.inputFile());
            validateWeComConfiguration(input);
            InitialIdentityBootstrapResult result = bootstrapService.execute(input, reason, requestId);
            LOGGER.info(
                    "initial identity bootstrap completed requestId={} directoryRunId={} outcome=SUCCEEDED",
                    requestId,
                    result.directoryRunId()
            );
            SpringApplication.exit(applicationContext, () -> 0);
        } catch (RuntimeException exception) {
            Failure failure = failure(exception);
            try {
                auditService.failed(
                        companyId,
                        requestId,
                        reason,
                        failure.stage(),
                        failure.errorCode(),
                        failure.directoryRunId()
                );
            } catch (RuntimeException auditFailure) {
                LOGGER.error(
                        "initial identity bootstrap failure audit failed requestId={} errorCode={}",
                        requestId,
                        "INITIAL_IDENTITY_BOOTSTRAP_AUDIT_FAILED"
                );
            }
            LOGGER.error(
                    "initial identity bootstrap failed requestId={} stage={} errorCode={} directoryRunId={}",
                    requestId,
                    failure.stage(),
                    failure.errorCode(),
                    failure.directoryRunId()
            );
            throw new IllegalStateException(
                    "Initial identity bootstrap failed requestId=" + requestId
                            + " errorCode=" + failure.errorCode()
            );
        }
    }

    private void validateRuntimeBoundary() {
        if (applicationContext instanceof WebApplicationContext
                || !"none".equalsIgnoreCase(environment.getProperty(
                        "spring.main.web-application-type", ""))) {
            throw rejected("RUNTIME", "INITIAL_IDENTITY_BOOTSTRAP_WEB_MODE_FORBIDDEN");
        }
        if (Arrays.stream(environment.getActiveProfiles())
                .noneMatch("prod"::equalsIgnoreCase)) {
            throw rejected("RUNTIME", "INITIAL_IDENTITY_BOOTSTRAP_PROD_PROFILE_REQUIRED");
        }
        if (environment.getProperty(
                "yumpoo.maintenance.app-manager.enabled", Boolean.class, false)) {
            throw rejected("RUNTIME", "INITIAL_IDENTITY_BOOTSTRAP_MAINTENANCE_CONFLICT");
        }
    }

    private void validateWeComConfiguration(InitialIdentityBootstrapInput input) {
        try {
            oauth.validateForEnabled();
            directory.validateForEnabled();
        } catch (RuntimeException exception) {
            throw rejected("CONFIGURATION", "INITIAL_IDENTITY_BOOTSTRAP_WECOM_CONFIG_INVALID");
        }
        boolean valid = oauth.isEnabled()
                && directory.isEnabled()
                && Objects.equals(oauth.getCorpId(), directory.getCorpId())
                && Objects.equals(input.expectedCorpId(), oauth.getCorpId())
                && independent(oauth.getAppSecret(), directory.getDirectorySecret())
                && independent(oauth.getAppSecret(), directory.getProfileSecret())
                && independent(directory.getDirectorySecret(), directory.getProfileSecret());
        if (!valid) {
            throw rejected("CONFIGURATION", "INITIAL_IDENTITY_BOOTSTRAP_WECOM_CONFIG_INVALID");
        }
    }

    private static boolean independent(String left, String right) {
        return left != null && !left.isBlank()
                && right != null && !right.isBlank()
                && !left.equals(right);
    }

    private static String normalizedReason(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw rejected("INPUT", "INITIAL_IDENTITY_BOOTSTRAP_REASON_INVALID");
        }
        return value.strip();
    }

    private static InitialIdentityBootstrapException rejected(String stage, String code) {
        return new InitialIdentityBootstrapException(
                stage,
                code,
                "Initial identity bootstrap was rejected"
        );
    }

    private static Failure failure(RuntimeException exception) {
        if (exception instanceof InitialIdentityBootstrapException bootstrap) {
            return new Failure(
                    bootstrap.stage(), bootstrap.errorCode(), bootstrap.directoryRunId());
        }
        if (exception instanceof ApplicationException application) {
            return new Failure("ROLE_BOOTSTRAP", application.errorCode().name(), null);
        }
        return new Failure(
                "UNEXPECTED",
                "INITIAL_IDENTITY_BOOTSTRAP_UNEXPECTED_FAILURE",
                null
        );
    }

    private record Failure(String stage, String errorCode, UUID directoryRunId) {
    }
}
