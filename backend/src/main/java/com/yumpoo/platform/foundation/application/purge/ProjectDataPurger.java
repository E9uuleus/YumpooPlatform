package com.yumpoo.platform.foundation.application.purge;

import java.util.UUID;

public interface ProjectDataPurger {
    String stage();
    int order();
    boolean purgeBatch(UUID companyId, UUID projectId, int limit);
}
