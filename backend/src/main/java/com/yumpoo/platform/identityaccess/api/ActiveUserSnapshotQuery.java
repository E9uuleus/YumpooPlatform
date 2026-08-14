package com.yumpoo.platform.identityaccess.api;

import java.util.Optional;
import java.util.UUID;

public interface ActiveUserSnapshotQuery {

    Optional<ActiveUserSnapshot> findByUserId(UUID userId);
}
