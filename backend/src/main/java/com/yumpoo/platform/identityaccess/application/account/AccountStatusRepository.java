package com.yumpoo.platform.identityaccess.application.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountStatusRepository {

    AccountStatusSnapshot change(AccountStatusChangeCommand command);

    Optional<AccountStatusSnapshot> disableFromDirectory(UUID companyId, UUID userId, String reason, Instant now);
}
