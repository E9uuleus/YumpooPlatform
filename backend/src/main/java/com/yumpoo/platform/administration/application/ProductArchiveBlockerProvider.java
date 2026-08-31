package com.yumpoo.platform.administration.application;

import java.util.UUID;

public interface ProductArchiveBlockerProvider {
    ProductArchiveBlockerSource source();
    ProductArchiveBlockerReport report(UUID companyId, UUID productId);
}
