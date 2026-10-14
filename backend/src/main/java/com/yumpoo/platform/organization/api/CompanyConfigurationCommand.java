package com.yumpoo.platform.organization.api;

import java.time.Instant;

public interface CompanyConfigurationCommand {
    /** Renames the Company when {@code expectedVersion} is current; false on a stale version. */
    boolean rename(String displayName, long expectedVersion, Instant now);
}
