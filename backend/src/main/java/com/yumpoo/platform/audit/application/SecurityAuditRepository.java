package com.yumpoo.platform.audit.application;

import java.util.List;
import java.util.UUID;

public interface SecurityAuditRepository {

    UUID append(SecurityAuditRecord record, String requestId, String correlationId);

    List<SecurityAuditStoredEvent> findByRequestId(
            UUID companyId, String requestId, int offset, int size
    );

    long countByRequestId(UUID companyId, String requestId);
}
