package com.yumpoo.platform.audit.api;

import java.util.UUID;

public interface SecurityAuditQueryPort {

    SecurityAuditPage findByRequestId(UUID companyId, String requestId, int page, int size);
}
