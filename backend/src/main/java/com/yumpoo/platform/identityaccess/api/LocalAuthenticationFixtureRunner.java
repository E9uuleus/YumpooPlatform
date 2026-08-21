package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleActor;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
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
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "yumpoo.auth.local", name = "enabled", havingValue = "true")
public final class LocalAuthenticationFixtureRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LocalAuthenticationFixtureRunner.class
    );
    private static final String REASON = "Local development identity fixture";

    private final Environment environment;
    private final LocalAuthenticationProperties properties;
    private final CompanyConfigurationQuery companyQuery;
    private final IdentityAcceptanceFixtureProvisioner provisioner;
    private final PlatformRoleMaintenanceUseCase maintenanceUseCase;
    private final PlatformRoleCommandPort roleCommands;
    private final PlatformRoleQuery roleQuery;
    private final Clock clock;

    public LocalAuthenticationFixtureRunner(
            Environment environment,
            LocalAuthenticationProperties properties,
            CompanyConfigurationQuery companyQuery,
            IdentityAcceptanceFixtureProvisioner provisioner,
            PlatformRoleMaintenanceUseCase maintenanceUseCase,
            PlatformRoleCommandPort roleCommands,
            PlatformRoleQuery roleQuery,
            Clock clock
    ) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.companyQuery = Objects.requireNonNull(companyQuery, "companyQuery must not be null");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner must not be null");
        this.maintenanceUseCase = Objects.requireNonNull(
                maintenanceUseCase,
                "maintenanceUseCase must not be null"
        );
        this.roleCommands = Objects.requireNonNull(roleCommands, "roleCommands must not be null");
        this.roleQuery = Objects.requireNonNull(roleQuery, "roleQuery must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("local-auth-fixture-" + UUID.randomUUID())
        )) {
            initialize();
        }
    }

    private void initialize() {
        validateRuntimeBoundary();
        UUID companyId = companyQuery.current().companyId();
        DirectoryMemberProvisioningResult localAdmin = provisioner.provision(
                properties.getMemberId(),
                properties.getDisplayName(),
                "Local Development"
        );
        DirectoryMemberProvisioningResult backupManager = provisioner.provision(
                properties.getBackupMemberId(),
                properties.getBackupDisplayName(),
                "Local Development"
        );

        Set<PlatformRoleCode> adminRoles = roleQuery.findActiveRoleCodes(
                companyId,
                localAdmin.userId()
        );
        Set<PlatformRoleCode> backupRoles = roleQuery.findActiveRoleCodes(
                companyId,
                backupManager.userId()
        );
        if (adminRoles.containsAll(Set.of(
                PlatformRoleCode.COMPANY_ADMIN,
                PlatformRoleCode.APP_MANAGER
        ))) {
            LOGGER.info("Local authentication fixture is ready for member {}",
                    properties.getMemberId());
            return;
        }

        ActorState actor;
        if (backupRoles.contains(PlatformRoleCode.APP_MANAGER)) {
            actor = new ActorState(
                    backupManager.userId(),
                    backupManager.authorizationVersion()
            );
        } else if (adminRoles.contains(PlatformRoleCode.APP_MANAGER)) {
            actor = new ActorState(localAdmin.userId(), localAdmin.authorizationVersion());
        } else {
            MaintenanceRoleActor manager = maintenanceUseCase.ensureAvailableAppManager(
                    companyId,
                    backupManager.userId(),
                    REASON
            );
            actor = new ActorState(manager.userId(), manager.authorizationVersion());
        }

        long targetRowVersion = localAdmin.rowVersion();
        if (!adminRoles.contains(PlatformRoleCode.COMPANY_ADMIN)) {
            PlatformRoleAssignmentMutation mutation = grant(
                    companyId,
                    localAdmin.userId(),
                    PlatformRoleCode.COMPANY_ADMIN,
                    targetRowVersion,
                    actor
            );
            targetRowVersion = mutation.userRowVersion();
            actor = actor.afterMutation(mutation);
        }
        if (!adminRoles.contains(PlatformRoleCode.APP_MANAGER)) {
            PlatformRoleAssignmentMutation mutation = grant(
                    companyId,
                    localAdmin.userId(),
                    PlatformRoleCode.APP_MANAGER,
                    targetRowVersion,
                    actor
            );
            actor = actor.afterMutation(mutation);
        }
        LOGGER.info("Local authentication fixture initialized for member {}",
                properties.getMemberId());
    }

    private PlatformRoleAssignmentMutation grant(
            UUID companyId,
            UUID targetUserId,
            PlatformRoleCode role,
            long expectedTargetRowVersion,
            ActorState actor
    ) {
        String commandIdentity = companyId + ":" + targetUserId + ":" + role
                + ":" + expectedTargetRowVersion;
        return roleCommands.grant(new PlatformRoleGrantCommand(
                companyId,
                targetUserId,
                role,
                expectedTargetRowVersion,
                new PlatformRoleCommandActor(
                        actor.userId(),
                        actor.authorizationVersion(),
                        clock.instant()
                ),
                UUID.nameUUIDFromBytes(("local-auth:" + commandIdentity)
                        .getBytes(StandardCharsets.UTF_8)),
                sha256(commandIdentity),
                REASON
        )).mutation();
    }

    private void validateRuntimeBoundary() {
        properties.validateForEnabled();
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("Local authentication is forbidden in prod");
        }
        if (!environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException("Local authentication requires the local profile");
        }
        if (environment.getProperty("yumpoo.wecom.oauth.enabled", Boolean.class, false)
                || environment.getProperty("yumpoo.wecom.directory.enabled", Boolean.class, false)
                || environment.getProperty("yumpoo.auth.controlled.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "Local authentication cannot run with WeCom or controlled identity providers"
            );
        }
        String address = environment.getProperty("server.address", "127.0.0.1").strip();
        if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(address)) {
            throw new IllegalStateException(
                    "Local authentication requires a loopback-only server address"
            );
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ActorState(UUID userId, long authorizationVersion) {

        private ActorState afterMutation(PlatformRoleAssignmentMutation mutation) {
            if (!userId.equals(mutation.userId())) {
                return this;
            }
            return new ActorState(userId, mutation.authorizationVersion());
        }
    }
}
