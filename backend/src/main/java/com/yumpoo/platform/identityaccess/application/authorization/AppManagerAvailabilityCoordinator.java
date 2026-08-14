package com.yumpoo.platform.identityaccess.application.authorization;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.identityaccess.application.audit.IdentitySecurityAuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

@Service
public class AppManagerAvailabilityCoordinator {

    public static final String MISSING_EVENT = "identity.app_manager_missing_detected";
    public static final String RESTORED_EVENT = "identity.app_manager_availability_restored";

    private final RoleGovernanceRepository repository;
    private final TransactionalEventPort eventPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IdentitySecurityAuditRecorder auditRecorder;

    public AppManagerAvailabilityCoordinator(
            RoleGovernanceRepository repository,
            TransactionalEventPort eventPort,
            ObjectMapper objectMapper,
            Clock clock,
            IdentitySecurityAuditRecorder auditRecorder
    ) {
        this.repository = repository;
        this.eventPort = eventPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AvailabilitySnapshot lock(UUID companyId) {
        GovernanceStateSnapshot state = repository.lockState(companyId);
        return new AvailabilitySnapshot(state, repository.countAvailableAppManagers(companyId));
    }

    public void protectLastAvailable(AvailabilitySnapshot before, boolean removesAvailableManager) {
        if (removesAvailableManager && before.availableCount() <= 1) {
            throw new ApplicationException(
                    StandardErrorCode.INVALID_STATE_TRANSITION,
                    "必须至少保留一名可用的 APP_MANAGER"
            );
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(
            AvailabilitySnapshot before,
            String triggerCode,
            UUID affectedUserId,
            EventActor actor
    ) {
        if (before.state().lifecycleStatus() == GovernanceLifecycleStatus.UNINITIALIZED) {
            return;
        }
        int afterCount = repository.countAvailableAppManagers(before.state().companyId());
        if (before.availableCount() > 0 && afterCount == 0) {
            GovernanceStateSnapshot changed = repository.markMissing(
                    before.state().companyId(), clock.instant());
            publish(MISSING_EVENT, changed, before.availableCount(), afterCount,
                    triggerCode, affectedUserId, actor);
        } else if (before.availableCount() == 0 && afterCount > 0) {
            GovernanceStateSnapshot changed = repository.markAvailable(
                    before.state().companyId(), false, clock.instant());
            publish(RESTORED_EVENT, changed, before.availableCount(), afterCount,
                    triggerCode, affectedUserId, actor);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public GovernanceStateSnapshot initializeAvailable(UUID companyId) {
        return repository.markAvailable(companyId, true, clock.instant());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public GovernanceStateSnapshot restoreAvailable(
            AvailabilitySnapshot before,
            String triggerCode,
            UUID affectedUserId,
            EventActor actor
    ) {
        GovernanceStateSnapshot changed = repository.markAvailable(
                before.state().companyId(), false, clock.instant());
        int afterCount = repository.countAvailableAppManagers(before.state().companyId());
        publish(RESTORED_EVENT, changed, before.availableCount(), afterCount,
                triggerCode, affectedUserId, actor);
        return changed;
    }

    private void publish(
            String eventType,
            GovernanceStateSnapshot state,
            int previousCount,
            int currentCount,
            String triggerCode,
            UUID affectedUserId,
            EventActor actor
    ) {
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previousAvailableCount", previousCount);
        payload.put("currentAvailableCount", currentCount);
        payload.put("triggerCode", triggerCode);
        payload.put("affectedUserId", affectedUserId);
        payload.put("detectedAt", now);
        eventPort.append(new EventDraft(
                eventType,
                1,
                "AppManagerGovernanceState",
                state.companyId(),
                state.eventVersion(),
                state.companyId(),
                actor,
                objectMapper.valueToTree(payload)
        ));
        auditRecorder.succeeded(
                state.companyId(),
                "app-manager-availability:" + state.eventVersion() + ":" + eventType,
                eventType.equals(MISSING_EVENT)
                        ? "APP_MANAGER_AVAILABILITY_MISSING" : "APP_MANAGER_AVAILABILITY_RESTORED",
                actor, Set.of(), "COMPANY", state.companyId(), actor.reasonReference(),
                Map.of("availableCount", previousCount),
                Map.of("availableCount", currentCount, "triggerCode", triggerCode,
                        "affectedUserId", affectedUserId), null, null, null);
    }
}
