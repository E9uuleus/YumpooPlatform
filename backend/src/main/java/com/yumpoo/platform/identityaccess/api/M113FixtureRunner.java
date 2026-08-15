package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleMode;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMutationResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureStateQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "yumpoo.verification.m1-13",
        name = "fixture-enabled",
        havingValue = "true"
)
public class M113FixtureRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(M113FixtureRunner.class);
    private static final String REASON = "M1-13 controlled acceptance fixture";

    private final Environment environment;
    private final IdentityAcceptanceFixtureStateQuery fixtureStateQuery;
    private final CompanyConfigurationQuery companyConfigurationQuery;
    private final IdentityAcceptanceFixtureProvisioner fixtureProvisioner;
    private final PlatformRoleMaintenanceUseCase maintenanceUseCase;
    private final PlatformRoleCommandPort roleCommandPort;
    private final Clock clock;

    public M113FixtureRunner(
            Environment environment,
            IdentityAcceptanceFixtureStateQuery fixtureStateQuery,
            CompanyConfigurationQuery companyConfigurationQuery,
            IdentityAcceptanceFixtureProvisioner fixtureProvisioner,
            PlatformRoleMaintenanceUseCase maintenanceUseCase,
            PlatformRoleCommandPort roleCommandPort,
            Clock clock
    ) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.fixtureStateQuery = Objects.requireNonNull(
                fixtureStateQuery,
                "fixtureStateQuery must not be null"
        );
        this.companyConfigurationQuery = Objects.requireNonNull(
                companyConfigurationQuery,
                "companyConfigurationQuery must not be null"
        );
        this.fixtureProvisioner = Objects.requireNonNull(
                fixtureProvisioner,
                "fixtureProvisioner must not be null"
        );
        this.maintenanceUseCase = Objects.requireNonNull(
                maintenanceUseCase,
                "maintenanceUseCase must not be null"
        );
        this.roleCommandPort = Objects.requireNonNull(
                roleCommandPort,
                "roleCommandPort must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m113-fixture-" + UUID.randomUUID())
        )) {
            initialize();
        }
    }

    private void initialize() {
        validateRuntimeBoundary();
        if (!fixtureStateQuery.current().pristine()) {
            throw new IllegalStateException("M1-13 fixture requires pristine identity facts");
        }

        String controlledMemberId = requireMemberId(
                environment.getProperty("yumpoo.auth.controlled.member-id"),
                "controlled member id"
        );
        String backupMemberId = requireMemberId(
                environment.getProperty("yumpoo.verification.m1-13.backup-member-id"),
                "backup member id"
        );
        if (controlledMemberId.equals(backupMemberId)) {
            throw new IllegalStateException("M1-13 fixture members must be distinct");
        }

        DirectoryMemberProvisioningResult controlled = fixtureProvisioner.provision(
                controlledMemberId,
                "M1-13 Controlled Company Admin"
        );
        DirectoryMemberProvisioningResult backup = fixtureProvisioner.provision(
                backupMemberId,
                "M1-13 Backup App Manager"
        );
        UUID companyId = companyConfigurationQuery.current().companyId();
        PlatformRoleMutationResult manager = maintenanceUseCase.execute(new MaintenanceRoleCommand(
                companyId,
                backup.userId(),
                MaintenanceRoleMode.BOOTSTRAP,
                REASON
        ));
        roleCommandPort.grant(new PlatformRoleGrantCommand(
                companyId,
                controlled.userId(),
                PlatformRoleCode.COMPANY_ADMIN,
                controlled.rowVersion(),
                new PlatformRoleCommandActor(
                        backup.userId(),
                        manager.authorizationVersion(),
                        clock.instant()
                ),
                UUID.randomUUID(),
                sha256("grant-company-admin:" + companyId + ":" + controlled.userId()),
                REASON
        ));
        LOGGER.info("M1-13 controlled acceptance fixture initialized");
    }

    private void validateRuntimeBoundary() {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("M1-13 fixture is forbidden in prod");
        }
        if (!environment.acceptsProfiles(Profiles.of("m1-13-e2e"))
                || !environment.acceptsProfiles(Profiles.of("local | test"))) {
            throw new IllegalStateException(
                    "M1-13 fixture requires m1-13-e2e and local/test profiles"
            );
        }
        if (!environment.getProperty(
                "yumpoo.auth.controlled.enabled",
                Boolean.class,
                false
        )) {
            throw new IllegalStateException("M1-13 fixture requires controlled authentication");
        }
        if (environment.getProperty(
                "yumpoo.maintenance.app-manager.enabled",
                Boolean.class,
                false
        )) {
            throw new IllegalStateException(
                    "M1-13 fixture cannot run with app-manager maintenance mode"
            );
        }
        requireMemberId(
                environment.getProperty("yumpoo.auth.controlled.corp-id"),
                "controlled corp id"
        );
    }

    private static String requireMemberId(String value, String field) {
        if (value == null || value.isBlank() || value.strip().length() > 256) {
            throw new IllegalStateException(field + " is invalid");
        }
        return value.strip();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
