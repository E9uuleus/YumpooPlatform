package com.yumpoo.platform.identityaccess.application.bootstrap;

import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.identityaccess.application.audit.IdentitySecurityAuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class InitialIdentityBootstrapAuditService {

    public static final String SYSTEM_CODE = "INITIAL_IDENTITY_BOOTSTRAP";

    private final IdentitySecurityAuditRecorder auditRecorder;

    public InitialIdentityBootstrapAuditService(IdentitySecurityAuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(
            UUID companyId,
            String requestId,
            String reasonReference,
            String stage,
            String errorCode,
            UUID directoryRunId
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stage", stage);
        if (directoryRunId != null) {
            summary.put("directoryRunId", directoryRunId);
        }
        auditRecorder.outcome(
                companyId,
                "initial-identity-bootstrap-failed:" + requestId,
                "INITIAL_IDENTITY_BOOTSTRAP_FAILED",
                SecurityAuditOutcome.FAILED,
                EventActor.system(SYSTEM_CODE),
                Set.of(),
                "COMPANY",
                companyId,
                reasonReference,
                null,
                summary,
                errorCode,
                null,
                null
        );
    }
}
