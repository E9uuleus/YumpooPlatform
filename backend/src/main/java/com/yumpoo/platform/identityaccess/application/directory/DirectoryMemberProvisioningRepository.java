package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.identityaccess.domain.identity.ExternalIdentityProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DirectoryMemberProvisioningRepository {

    void acquireProvisionLock(
            UUID companyId,
            ExternalIdentityProvider provider,
            String externalUserId
    );

    Optional<DirectoryMemberBinding> findByExternalIdentity(
            UUID companyId,
            ExternalIdentityProvider provider,
            String externalUserId
    );

    DirectoryMemberBinding create(
            UUID companyId,
            WeComMemberProfile profile,
            Instant now
    );

    DirectoryMemberBinding refresh(
            DirectoryMemberBinding current,
            WeComMemberProfile profile,
            Instant now
    );
}
