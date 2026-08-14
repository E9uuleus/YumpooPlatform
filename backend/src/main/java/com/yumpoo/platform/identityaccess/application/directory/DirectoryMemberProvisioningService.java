package com.yumpoo.platform.identityaccess.application.directory;

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
    private final Clock clock;

    public DirectoryMemberProvisioningService(
            CompanyConfigurationQuery companyConfigurationQuery,
            DirectoryMemberProvisioningRepository repository,
            Clock clock
    ) {
        this.companyConfigurationQuery = Objects.requireNonNull(
                companyConfigurationQuery,
                "companyConfigurationQuery must not be null"
        );
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public DirectoryMemberProvisioningResult provisionOrRefresh(WeComMemberProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        UUID companyId = companyConfigurationQuery.current().companyId();
        repository.acquireProvisionLock(
                companyId,
                ExternalIdentityProvider.WECOM,
                profile.externalUserId()
        );

        Instant now = clock.instant();
        return repository.findByExternalIdentity(
                        companyId,
                        ExternalIdentityProvider.WECOM,
                        profile.externalUserId()
                )
                .map(current -> refresh(current, profile, now))
                .orElseGet(() -> result(
                        repository.create(companyId, profile, now),
                        DirectoryMemberProvisioningOutcome.CREATED
                ));
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
