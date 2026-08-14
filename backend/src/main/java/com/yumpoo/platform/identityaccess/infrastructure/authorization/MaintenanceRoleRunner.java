package com.yumpoo.platform.identityaccess.infrastructure.authorization;

import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleMode;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMutationResult;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "yumpoo.maintenance.app-manager",
        name = "enabled",
        havingValue = "true"
)
public class MaintenanceRoleRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceRoleRunner.class);

    private final MaintenanceRoleRunnerProperties properties;
    private final CompanyConfigurationQuery companyConfigurationQuery;
    private final PlatformRoleMaintenanceUseCase maintenanceUseCase;
    private final ConfigurableApplicationContext applicationContext;

    public MaintenanceRoleRunner(
            MaintenanceRoleRunnerProperties properties,
            CompanyConfigurationQuery companyConfigurationQuery,
            PlatformRoleMaintenanceUseCase maintenanceUseCase,
            ConfigurableApplicationContext applicationContext
    ) {
        this.properties = properties;
        this.companyConfigurationQuery = companyConfigurationQuery;
        this.maintenanceUseCase = maintenanceUseCase;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        String requestId = UUID.randomUUID().toString();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root(requestId))) {
            execute(requestId);
        }
    }

    private void execute(String requestId) {
        MaintenanceRoleMode mode = MaintenanceRoleMode.valueOf(
                requireText(properties.mode(), "maintenance mode").toUpperCase());
        PlatformRoleMutationResult result = maintenanceUseCase.execute(new MaintenanceRoleCommand(
                companyConfigurationQuery.current().companyId(),
                Objects.requireNonNull(properties.targetUserId(), "targetUserId is required"),
                mode,
                requireText(properties.reasonReference(), "reasonReference")
        ));
        LOGGER.info(
                "app-manager maintenance completed requestId={} mode={} assignmentId={} outcome={}",
                requestId,
                mode,
                result.assignmentId(),
                result.status()
        );
        SpringApplication.exit(applicationContext, () -> 0);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}
