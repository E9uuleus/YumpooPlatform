package com.yumpoo.platform.identityaccess.application.audit;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventActorType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class IdentitySecurityAuditRecorder {

    private final SecurityAuditAppendPort auditPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdentitySecurityAuditRecorder(
            SecurityAuditAppendPort auditPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.auditPort = auditPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public UUID succeeded(
            UUID companyId,
            String factKey,
            String action,
            EventActor actor,
            Set<String> roleSnapshot,
            String targetType,
            Object targetId,
            String reasonReference,
            Map<String, ?> before,
            Map<String, ?> after,
            UUID commandId,
            String clientType,
            String clientVersion
    ) {
        return auditPort.append(draft(
                companyId, factKey, action, SecurityAuditOutcome.SUCCEEDED,
                actor(actor, roleSnapshot), targetType, targetId, reasonReference,
                before, after, null, commandId, clientType, clientVersion));
    }

    public UUID outcome(
            UUID companyId,
            String factKey,
            String action,
            SecurityAuditOutcome outcome,
            EventActor actor,
            Set<String> roleSnapshot,
            String targetType,
            Object targetId,
            String reasonReference,
            Map<String, ?> before,
            Map<String, ?> after,
            String errorCode,
            String clientType,
            String clientVersion
    ) {
        return auditPort.append(draft(
                companyId, factKey, action, outcome, actor(actor, roleSnapshot),
                targetType, targetId, reasonReference, before, after, errorCode,
                null, clientType, clientVersion));
    }

    public UUID failedIndependent(
            UUID companyId,
            String factKey,
            String action,
            SecurityAuditActor actor,
            String targetType,
            Object targetId,
            String reasonReference,
            String errorCode,
            String clientType,
            String clientVersion
    ) {
        return auditPort.appendIndependent(draft(
                companyId, factKey, action, SecurityAuditOutcome.FAILED, actor,
                targetType, targetId, reasonReference, null, null, errorCode,
                null, clientType, clientVersion));
    }

    public SecurityAuditActor actor(EventActor actor, Set<String> roleSnapshot) {
        if (actor.type() == EventActorType.SYSTEM) {
            return SecurityAuditActor.system(actor.systemCode());
        }
        return SecurityAuditActor.user(actor.userId(), roleSnapshot);
    }

    private SecurityAuditDraft draft(
            UUID companyId,
            String factKey,
            String action,
            SecurityAuditOutcome outcome,
            SecurityAuditActor actor,
            String targetType,
            Object targetId,
            String reasonReference,
            Map<String, ?> before,
            Map<String, ?> after,
            String errorCode,
            UUID commandId,
            String clientType,
            String clientVersion
    ) {
        return new SecurityAuditDraft(
                companyId, factKey, action, outcome, actor, targetType,
                String.valueOf(targetId), reasonReference, json(before), json(after),
                errorCode, commandId, clientType, clientVersion, clock.instant());
    }

    private JsonNode json(Map<String, ?> value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }
}
