package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.identityaccess.domain.identity.ExternalIdentityProvider;
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
                .orElseGet(() -> created(repository.create(companyId, profile, now)));
    }

    private DirectoryMemberProvisioningResult refresh(
            DirectoryMemberBinding current,
            WeComMemberProfile profile,
            Instant now
    ) {
        boolean profileChanged = !current.user().displayName().equals(profile.displayName())
                || !Objects.equals(current.user().email(), profile.email())
                || !Objects.equals(current.user().mobile(), profile.mobile())
                || !Objects.equals(
                        current.user().departmentSummary(),
                        profile.departmentSummary()
                )
                || !current.externalIdentity().rawProfileHash().equals(profile.rawProfileHash());
        DirectoryMemberBinding refreshed = repository.refresh(current, profile, now);
        return result(refreshed, false, profileChanged);
    }

    private static DirectoryMemberProvisioningResult created(DirectoryMemberBinding binding) {
        return result(binding, true, true);
    }

    private static DirectoryMemberProvisioningResult result(
            DirectoryMemberBinding binding,
            boolean created,
            boolean profileChanged
    ) {
        return new DirectoryMemberProvisioningResult(
                binding.user().id(),
                binding.externalIdentity().id(),
                binding.user().employmentStatus(),
                binding.user().accountStatus(),
                binding.user().rowVersion(),
                created,
                profileChanged
        );
    }
}
