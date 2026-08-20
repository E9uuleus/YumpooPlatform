package com.yumpoo.platform.identityaccess.application.session;

import java.util.UUID;

public record MinimalUserRecord(UUID userId, UUID companyId, String displayName,
                                String employmentStatus, String accountStatus) {}
