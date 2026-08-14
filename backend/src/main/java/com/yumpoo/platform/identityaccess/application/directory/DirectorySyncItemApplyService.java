package com.yumpoo.platform.identityaccess.application.directory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

@Service
public class DirectorySyncItemApplyService {

    private final DirectoryMemberProvisioningService provisioningService;
    private final DirectorySyncRepository repository;

    public DirectorySyncItemApplyService(
            DirectoryMemberProvisioningService provisioningService,
            DirectorySyncRepository repository
    ) {
        this.provisioningService = Objects.requireNonNull(
                provisioningService,
                "provisioningService must not be null"
        );
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(
            UUID runId,
            UUID leaseToken,
            WeComMemberProfile profile,
            Duration leaseDuration
    ) {
        DirectoryMemberProvisioningResult result = provisioningService.provisionOrRefresh(profile);
        repository.markApplied(runId, leaseToken, profile, result, leaseDuration);
    }
}
