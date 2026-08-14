package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.identityaccess.application.authorization.AppManagerAvailabilityCoordinator;
import com.yumpoo.platform.identityaccess.application.authorization.AvailabilitySnapshot;
import com.yumpoo.platform.identityaccess.domain.identity.ExternalIdentityProvider;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class DirectoryMemberProvisioningService {

    private final CompanyConfigurationQuery companyConfigurationQuery;
    private final DirectoryMemberProvisioningRepository repository;
    private final AppManagerAvailabilityCoordinator availabilityCoordinator;
    private final Clock clock;

    public DirectoryMemberProvisioningService(
            CompanyConfigurationQuery companyConfigurationQuery,
            DirectoryMemberProvisioningRepository repository,
            AppManagerAvailabilityCoordinator availabilityCoordinator,
            Clock clock
    ) {
        this.companyConfigurationQuery = Objects.requireNonNull(
                companyConfigurationQuery,
                "companyConfigurationQuery must not be null"
        );
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.availabilityCoordinator = Objects.requireNonNull(
                availabilityCoordinator, "availabilityCoordinator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public DirectoryMemberProvisioningResult provisionOrRefresh(WeComMemberProfile profile) {
        return provisionOrRefresh(profile, EventActor.system("DIRECTORY_PROVISIONING"));
    }

    @Transactional
    public DirectoryMemberProvisioningResult provisionOrRefresh(
            WeComMemberProfile profile,
            EventActor actor
    ) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        UUID companyId = companyConfigurationQuery.current().companyId();
        AvailabilitySnapshot availabilityBefore = availabilityCoordinator.lock(companyId);
        repository.acquireProvisionLock(
                companyId,
                ExternalIdentityProvider.WECOM,
                profile.externalUserId()
        );

        Instant now = clock.instant();
        DirectoryMemberProvisioningResult result = repository.findByExternalIdentity(
                        companyId,
                        ExternalIdentityProvider.WECOM,
                        profile.externalUserId()
                )
                .map(current -> refresh(current, profile, now))
                .orElseGet(() -> result(
                        repository.create(companyId, profile, now),
                        DirectoryMemberProvisioningOutcome.CREATED
                ));
        availabilityCoordinator.reconcile(
                availabilityBefore,
                result.outcome() == DirectoryMemberProvisioningOutcome.RETURNED
                        ? "EMPLOYMENT_RETURNED" : "DIRECTORY_MEMBER_REFRESHED",
                result.userId(),
                actor
        );
        return result;
    }

    private DirectoryMemberProvisioningResult refresh(
            DirectoryMemberBinding current,
            WeComMemberProfile profile,
            Instant now
    ) {
        String effectiveEmail = profile.email().applyTo(current.user().email());
        String effectiveMobile = profile.mobile().applyTo(current.user().mobile());
        WeComMemberProfile effectiveProfile = new WeComMemberProfile(
                profile.externalUserId(),
                profile.displayName(),
                resolved(effectiveEmail),
                resolved(effectiveMobile),
                profile.departmentSummary(),
                profile.rawProfileHash()
        );
        boolean profileChanged = !current.user().displayName().equals(profile.displayName())
                || !Objects.equals(current.user().email(), effectiveEmail)
                || !Objects.equals(current.user().mobile(), effectiveMobile)
                || !Objects.equals(
                        current.user().departmentSummary(),
                        profile.departmentSummary()
                )
                || !current.externalIdentity().rawProfileHash().equals(profile.rawProfileHash());
        boolean returned = current.user().employmentStatus() == EmploymentStatus.LEFT
                || current.externalIdentity().providerEmploymentStatus() == EmploymentStatus.LEFT;
        DirectoryMemberBinding refreshed = repository.refresh(current, effectiveProfile, now);
        DirectoryMemberProvisioningOutcome outcome = returned
                ? DirectoryMemberProvisioningOutcome.RETURNED
                : profileChanged
                        ? DirectoryMemberProvisioningOutcome.UPDATED
                        : DirectoryMemberProvisioningOutcome.UNCHANGED;
        return result(refreshed, outcome);
    }

    private static DirectoryOptionalField resolved(String value) {
        return value == null
                ? DirectoryOptionalField.clear()
                : DirectoryOptionalField.present(value);
    }

    private static DirectoryMemberProvisioningResult result(
            DirectoryMemberBinding binding,
            DirectoryMemberProvisioningOutcome outcome
    ) {
        return new DirectoryMemberProvisioningResult(
                binding.user().id(),
                binding.externalIdentity().id(),
                binding.user().employmentStatus(),
                binding.user().accountStatus(),
                binding.user().authorizationVersion(),
                binding.user().rowVersion(),
                outcome
        );
    }
}
