package com.yumpoo.platform.identityaccess.application.authentication;

import java.util.Optional;
import java.util.UUID;

public interface AuthenticationUserRepository {

    Optional<AuthenticationUser> lockByWeComIdentity(UUID companyId, String externalUserId);

    Optional<AuthenticationUser> findByUserId(UUID userId);
}
