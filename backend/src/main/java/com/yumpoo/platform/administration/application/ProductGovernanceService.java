package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.catalog.api.ProductCommandPort;
import com.yumpoo.platform.catalog.api.ProductLifecycleMutation;
import com.yumpoo.platform.catalog.api.ProductLifecycleStatus;
import com.yumpoo.platform.catalog.api.ProductMutationResult;
import com.yumpoo.platform.catalog.api.ProductOwnerReassignmentMutation;
import com.yumpoo.platform.catalog.api.ProductSnapshot;
import com.yumpoo.platform.catalog.api.ProductSnapshotQuery;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshot;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductGovernanceService {

    private static final String ARCHIVED_EVENT = "catalog.product_archived";
    private static final String RESTORED_EVENT = "catalog.product_restored";
    private static final String OWNER_REASSIGNED_EVENT = "catalog.product_owner_reassigned";

    private final ProductSnapshotQuery snapshotQuery;
    private final ProductCommandPort commandPort;
    private final ActiveUserSnapshotQuery activeUserQuery;
    private final IdempotentCommandExecutor idempotentCommandExecutor;
    private final TransactionalEventPort eventPort;
    private final SecurityAuditAppendPort auditPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProductGovernanceService(
            ProductSnapshotQuery snapshotQuery,
            ProductCommandPort commandPort,
            ActiveUserSnapshotQuery activeUserQuery,
            IdempotentCommandExecutor idempotentCommandExecutor,
            TransactionalEventPort eventPort,
            SecurityAuditAppendPort auditPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.snapshotQuery = snapshotQuery;
        this.commandPort = commandPort;
        this.activeUserQuery = activeUserQuery;
        this.idempotentCommandExecutor = idempotentCommandExecutor;
        this.eventPort = eventPort;
        this.auditPort = auditPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ProductSnapshot findForArchive(CurrentActor actor, UUID productId) {
        ProductSnapshot product = requiredVisible(actor, productId);
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)
                && !product.ownerUserId().equals(actor.userId())) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        return product;
    }

    public ProductSnapshot findForAdministration(CurrentActor actor, UUID productId) {
        requireCompanyAdmin(actor);
        return snapshotQuery.find(actor.companyId(), productId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    public IdempotencyExecutionResult archive(ProductLifecycleGovernanceCommand command) {
        findForArchive(command.actor(), command.productId());
        return lifecycle(command, true);
    }

    public IdempotencyExecutionResult restore(ProductLifecycleGovernanceCommand command) {
        requireCompanyAdmin(command.actor());
        return lifecycle(command, false);
    }

    public IdempotencyExecutionResult reassignOwner(ProductOwnerReassignmentCommand command) {
        requireCompanyAdmin(command.actor());
        requireValidReason(command.reason());
        IdempotencyCommand idempotency = new IdempotencyCommand(
                new IdempotencyScope(command.actor().userId(), "POST", "reassignProductOwner",
                        command.idempotencyKey()), command.requestHash());
        return idempotentCommandExecutor.execute(idempotency, () -> {
            ProductSnapshot visible = findForAdministration(command.actor(), command.productId());
            if (visible.ownerUserId().equals(command.newOwnerUserId())) {
                throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
            }
            requireAvailableOwner(visible.companyId(), command.newOwnerUserId(), false);
            ProductMutationResult result = commandPort.reassignOwner(
                    new ProductOwnerReassignmentMutation(command.actor().companyId(),
                            command.productId(), command.expectedRowVersion(),
                            command.newOwnerUserId(), command.actor().userId()));
            appendOwnerReassigned(result, command.actor());
            appendOwnerAudit(result, command);
            return stored(result.after());
        });
    }

    private IdempotencyExecutionResult lifecycle(
            ProductLifecycleGovernanceCommand command,
            boolean archive
    ) {
        String operation = archive ? "archiveProduct" : "restoreProduct";
        IdempotencyCommand idempotency = new IdempotencyCommand(
                new IdempotencyScope(command.actor().userId(), "POST", operation,
                        command.idempotencyKey()), command.requestHash());
        return idempotentCommandExecutor.execute(idempotency, () -> {
            if (!archive) {
                ProductSnapshot product = findForAdministration(command.actor(), command.productId());
                requireAvailableOwner(product.companyId(), product.ownerUserId(), true);
            }
            ProductLifecycleMutation mutation = new ProductLifecycleMutation(
                    command.actor().companyId(), command.productId(), command.expectedRowVersion(),
                    command.actor().userId());
            ProductMutationResult result = archive
                    ? commandPort.archive(mutation)
                    : commandPort.restore(mutation);
            appendLifecycle(archive ? ARCHIVED_EVENT : RESTORED_EVENT, result, command.actor());
            return stored(result.after());
        });
    }

    private void appendLifecycle(String eventType, ProductMutationResult result, CurrentActor actor) {
        Map<String, Object> payload = safePayload(result.after());
        payload.put("fromStatus", result.before().status().name());
        payload.put("toStatus", result.after().status().name());
        eventPort.append(new EventDraft(eventType, 1, "Product", result.after().productId(),
                result.after().rowVersion(), result.after().companyId(),
                EventActor.user(actor.userId()), objectMapper.valueToTree(payload)));
    }

    private void appendOwnerReassigned(ProductMutationResult result, CurrentActor actor) {
        Map<String, Object> payload = safePayload(result.after());
        payload.put("previousOwnerUserId", result.before().ownerUserId());
        payload.put("newOwnerUserId", result.after().ownerUserId());
        eventPort.append(new EventDraft(OWNER_REASSIGNED_EVENT, 1, "Product",
                result.after().productId(), result.after().rowVersion(), result.after().companyId(),
                EventActor.user(actor.userId()), objectMapper.valueToTree(payload)));
    }

    private void appendOwnerAudit(
            ProductMutationResult result,
            ProductOwnerReassignmentCommand command
    ) {
        auditPort.append(new SecurityAuditDraft(
                command.actor().companyId(),
                "product-owner:" + result.after().productId() + ":" + result.after().rowVersion(),
                "PRODUCT_OWNER_REASSIGNED",
                SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(command.actor().userId(), roleNames(command.actor())),
                "PRODUCT",
                result.after().productId().toString(),
                command.reason().strip(),
                objectMapper.valueToTree(Map.of(
                        "ownerUserId", result.before().ownerUserId(),
                        "rowVersion", result.before().rowVersion())),
                objectMapper.valueToTree(Map.of(
                        "ownerUserId", result.after().ownerUserId(),
                        "rowVersion", result.after().rowVersion())),
                null,
                command.idempotencyKey(),
                command.clientType(),
                command.clientVersion(),
                clock.instant()));
    }

    private Map<String, Object> safePayload(ProductSnapshot product) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", product.productId());
        payload.put("code", product.code());
        payload.put("name", product.name());
        payload.put("status", product.status().name());
        payload.put("ownerUserId", product.ownerUserId());
        return payload;
    }

    private StoredCommandResult stored(ProductSnapshot product) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", product.productId());
        body.put("code", product.code());
        body.put("name", product.name());
        body.put("description", product.description());
        body.put("status", product.status().name());
        body.put("ownerUserId", product.ownerUserId());
        body.put("rowVersion", product.rowVersion());
        try {
            return new StoredCommandResult(200, objectMapper.writeValueAsString(body),
                    product.productId(), StrongEtag.format(product.rowVersion()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("product response serialization failed", exception);
        }
    }

    private ProductSnapshot requiredVisible(CurrentActor actor, UUID productId) {
        requireActiveActor(actor);
        return snapshotQuery.findVisible(actor, productId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireAvailableOwner(UUID companyId, UUID ownerUserId, boolean lifecycle) {
        ActiveUserSnapshot owner = activeUserQuery.findByUserId(ownerUserId).orElse(null);
        if (owner == null || !owner.companyId().equals(companyId) || !owner.activeAndEnabled()) {
            if (lifecycle) {
                throw ApplicationException.withReason(
                        StandardErrorCode.INVALID_STATE_TRANSITION, "OWNER_MISSING");
            }
            throw ApplicationException.validation(new FieldViolation(
                    "ownerUserId", "INVALID_OWNER", "负责人必须是本企业有效成员"));
        }
    }

    private static void requireValidReason(String value) {
        if (value == null || value.strip().length() < 10 || value.strip().length() > 500) {
            throw ApplicationException.validation(new FieldViolation(
                    "reason", "INVALID_LENGTH", "重指派理由长度必须在 10 到 500 字符之间"));
        }
    }

    private static void requireActiveActor(CurrentActor actor) {
        if (actor == null) {
            throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private static void requireCompanyAdmin(CurrentActor actor) {
        requireActiveActor(actor);
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
    }

    private static Set<String> roleNames(CurrentActor actor) {
        return actor.platformRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
