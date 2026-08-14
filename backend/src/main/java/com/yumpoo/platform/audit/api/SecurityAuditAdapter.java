package com.yumpoo.platform.audit.api;

import com.yumpoo.platform.audit.application.SecurityAuditService;
import com.yumpoo.platform.audit.application.SecurityAuditRecord;
import com.yumpoo.platform.audit.application.SecurityAuditResultPage;
import com.yumpoo.platform.audit.application.SecurityAuditStoredEvent;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SecurityAuditAdapter implements SecurityAuditAppendPort, SecurityAuditQueryPort {

    private final SecurityAuditService service;

    public SecurityAuditAdapter(SecurityAuditService service) {
        this.service = service;
    }

    @Override
    public UUID append(SecurityAuditDraft draft) {
        return service.append(toRecord(draft));
    }

    @Override
    public UUID appendIndependent(SecurityAuditDraft draft) {
        return service.appendIndependent(toRecord(draft));
    }

    @Override
    public SecurityAuditPage findByRequestId(UUID companyId, String requestId, int page, int size) {
        SecurityAuditResultPage result = service.findByRequestId(companyId, requestId, page, size);
        return new SecurityAuditPage(
                result.items().stream().map(SecurityAuditAdapter::toView).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    private static SecurityAuditRecord toRecord(SecurityAuditDraft draft) {
        return new SecurityAuditRecord(
                draft.companyId(), draft.factKey(), draft.action(), draft.outcome().name(),
                draft.actor().type(), draft.actor().userId(), draft.actor().systemCode(),
                draft.actor().roleSnapshot(), draft.targetType(), draft.targetId(),
                draft.reasonReference(), draft.beforeSummary(), draft.afterSummary(),
                draft.errorCode(), draft.commandId(), draft.clientType(), draft.clientVersion(),
                draft.occurredAt());
    }

    private static SecurityAuditEventView toView(SecurityAuditStoredEvent event) {
        return new SecurityAuditEventView(
                event.id(), event.companyId(), event.factKey(), event.action(),
                SecurityAuditOutcome.valueOf(event.outcome()), event.actorType(),
                event.actorUserId(), event.actorSystemCode(), event.actorRoles(),
                event.targetType(), event.targetId(), event.reasonReference(),
                event.beforeSummary(), event.afterSummary(), event.errorCode(), event.commandId(),
                event.requestId(), event.correlationId(), event.clientType(), event.clientVersion(),
                event.occurredAt());
    }
}
