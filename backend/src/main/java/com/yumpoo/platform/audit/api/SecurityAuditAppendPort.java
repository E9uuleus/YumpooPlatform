package com.yumpoo.platform.audit.api;

import java.util.UUID;

public interface SecurityAuditAppendPort {

    UUID append(SecurityAuditDraft draft);

    UUID appendIndependent(SecurityAuditDraft draft);
}
