package com.yumpoo.platform.catalog.application.product;

import com.yumpoo.platform.catalog.domain.product.Product;
import com.yumpoo.platform.catalog.domain.product.ProductStatus;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageResponse;
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
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProductService {

    private static final String CREATED_EVENT = "catalog.product_created";
    private static final String UPDATED_EVENT = "catalog.product_updated";

    private final ProductRepository repository;
    private final ActiveUserSnapshotQuery activeUserQuery;
    private final MinimalUserSnapshotQuery minimalUsers;
    private final IdempotentCommandExecutor idempotentCommandExecutor;
    private final TransactionalEventPort eventPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProductService(
            ProductRepository repository,
            ActiveUserSnapshotQuery activeUserQuery,
            MinimalUserSnapshotQuery minimalUsers,
            IdempotentCommandExecutor idempotentCommandExecutor,
            TransactionalEventPort eventPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.activeUserQuery = activeUserQuery;
        this.minimalUsers = minimalUsers;
        this.idempotentCommandExecutor = idempotentCommandExecutor;
        this.eventPort = eventPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OffsetPageResponse<ProductView> findAll(
            CurrentActor actor,
            ProductListStatus status,
            OffsetPageRequest page
    ) {
        return findAll(actor, status, null, page);
    }

    @Transactional(readOnly = true)
    public OffsetPageResponse<ProductView> findAll(
            CurrentActor actor,
            ProductListStatus status,
            String query,
            OffsetPageRequest page
    ) {
        requireActiveActor(actor);
        Objects.requireNonNull(status, "status must not be null");
        String normalized = query == null ? null : query.strip();
        if (normalized != null && normalized.length() > 80) {
            throw ApplicationException.validation(new FieldViolation(
                    "query", "INVALID_LENGTH", "Product 搜索关键字最多 80 个字符"));
        }
        if (normalized != null && normalized.isEmpty()) normalized = null;
        ProductPageResult result = repository.findVisible(actor, status, normalized, page);
        Map<UUID, MinimalUserSnapshot> owners = minimalUsers.findByUserIds(actor.companyId(),
                result.items().stream().map(Product::ownerUserId)
                        .collect(java.util.stream.Collectors.toSet()));
        return OffsetPageResponse.of(result.items().stream().map(product -> view(product, actor,
                        displayName(owners, product.ownerUserId()))).toList(),
                page, result.totalElements());
    }

    @Transactional(readOnly = true)
    public ProductView findVisible(CurrentActor actor, UUID productId) {
        requireActiveActor(actor);
        Product product = requiredVisible(actor, productId);
        return view(product, actor, displayName(product));
    }

    @Transactional(readOnly = true)
    public ProductApplicationSnapshot findVisibleSnapshot(CurrentActor actor, UUID productId) {
        requireActiveActor(actor);
        return snapshot(requiredVisible(actor, productId));
    }

    @Transactional(readOnly = true)
    public Optional<ProductApplicationSnapshot> findSnapshot(UUID companyId, UUID productId) {
        return repository.findById(
                Objects.requireNonNull(companyId, "companyId must not be null"),
                Objects.requireNonNull(productId, "productId must not be null"))
                .map(ProductService::snapshot);
    }

    @Transactional(readOnly = true)
    public List<ProductApplicationSnapshot> findActiveByOwner(UUID companyId, UUID ownerUserId) {
        return repository.findByOwner(companyId, ownerUserId, ProductStatus.ACTIVE).stream()
                .map(ProductService::snapshot)
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductApplicationSnapshot lockForFactWrite(CurrentActor actor, UUID productId) {
        requiredVisible(actor, productId);
        Product product = repository.lockByIdForShare(actor.companyId(), productId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requiredVisible(actor, productId);
        return snapshot(product);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductApplicationSnapshot lockForArchive(ProductLifecycleChange mutation) {
        Product product = requiredLocked(mutation.companyId(), mutation.productId());
        requireVersion(product, mutation.expectedRowVersion());
        requireStatus(product, ProductStatus.ACTIVE);
        return snapshot(product);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductApplicationSnapshot lockForRestore(ProductLifecycleChange mutation) {
        Product product = requiredLocked(mutation.companyId(), mutation.productId());
        requireVersion(product, mutation.expectedRowVersion());
        requireStatus(product, ProductStatus.ARCHIVED);
        return snapshot(product);
    }

    public IdempotencyExecutionResult create(ProductCreateCommand command) {
        requireCompanyAdmin(command.actor());
        requireValidOwner(command.actor().companyId(), command.ownerUserId());
        IdempotencyCommand idempotency = new IdempotencyCommand(
                new IdempotencyScope(command.actor().userId(), "POST", "createProduct",
                        command.idempotencyKey()), command.requestHash());
        return idempotentCommandExecutor.execute(idempotency, () -> {
            Instant now = clock.instant();
            Product product = Product.create(UUID.randomUUID(), command.actor().companyId(),
                    command.code(), command.name(), command.description(), command.ownerUserId(),
                    command.actor().userId(), now);
            if (!repository.insert(product)) {
                throw ApplicationException.validation(new FieldViolation(
                        "code", "ALREADY_EXISTS", "Product 编码已存在"));
            }
            appendCreated(product, command.actor());
            return stored(201, product, command.actor());
        });
    }

    @Transactional
    public ProductView update(ProductUpdateCommand command) {
        Product before = requiredVisible(command.actor(), command.productId());
        if (!before.ownerUserId().equals(command.actor().userId())
                && !command.actor().hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        requireVersion(before, command.expectedRowVersion());
        if (before.status() != ProductStatus.ACTIVE) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        if (before.hasSameDetails(command.name(), command.description())) {
            return view(before, command.actor(), displayName(before));
        }
        Product candidate = before.updateDetails(command.name(), command.description(),
                command.actor().userId(), clock.instant());
        Product after = repository.updateDetails(candidate, command.expectedRowVersion())
                .orElseThrow(() -> conditionalFailure(before.companyId(), before.id(),
                        command.expectedRowVersion(), ProductStatus.ACTIVE));
        appendUpdated(before, after, command.actor());
        return view(after, command.actor(), displayName(after));
    }

    @Transactional
    public ProductChangeResult archive(ProductLifecycleChange mutation) {
        Product before = requiredLocked(mutation.companyId(), mutation.productId());
        requireVersion(before, mutation.expectedRowVersion());
        requireStatus(before, ProductStatus.ACTIVE);
        Product candidate = before.archive(mutation.actorUserId(), clock.instant());
        Product after = repository.changeStatus(candidate, ProductStatus.ACTIVE,
                        mutation.expectedRowVersion())
                .orElseThrow(() -> conditionalFailure(before.companyId(), before.id(),
                        mutation.expectedRowVersion(), ProductStatus.ACTIVE));
        return new ProductChangeResult(snapshot(before), snapshot(after));
    }

    @Transactional
    public ProductChangeResult restore(ProductLifecycleChange mutation) {
        Product before = requiredLocked(mutation.companyId(), mutation.productId());
        requireVersion(before, mutation.expectedRowVersion());
        requireStatus(before, ProductStatus.ARCHIVED);
        Product candidate = before.restore(mutation.actorUserId(), clock.instant());
        Product after = repository.changeStatus(candidate, ProductStatus.ARCHIVED,
                        mutation.expectedRowVersion())
                .orElseThrow(() -> conditionalFailure(before.companyId(), before.id(),
                        mutation.expectedRowVersion(), ProductStatus.ARCHIVED));
        return new ProductChangeResult(snapshot(before), snapshot(after));
    }

    @Transactional
    public ProductChangeResult reassignOwner(ProductOwnerChange mutation) {
        Product before = requiredLocked(mutation.companyId(), mutation.productId());
        requireVersion(before, mutation.expectedRowVersion());
        if (before.ownerUserId().equals(mutation.newOwnerUserId())) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        Product candidate = before.reassignOwner(mutation.newOwnerUserId(),
                mutation.actorUserId(), clock.instant());
        Product after = repository.reassignOwner(candidate, mutation.expectedRowVersion())
                .orElseThrow(() -> conditionalFailure(before.companyId(), before.id(),
                        mutation.expectedRowVersion(), before.status()));
        return new ProductChangeResult(snapshot(before), snapshot(after));
    }

    public void requireValidOwner(UUID companyId, UUID ownerUserId) {
        ActiveUserSnapshot owner = activeUserQuery.findByUserId(ownerUserId).orElse(null);
        if (owner == null || !owner.companyId().equals(companyId) || !owner.activeAndEnabled()) {
            throw ApplicationException.validation(new FieldViolation(
                    "ownerUserId", "INVALID_OWNER", "负责人必须是本企业有效成员"));
        }
    }

    private void appendCreated(Product product, CurrentActor actor) {
        Map<String, Object> payload = safePayload(product);
        eventPort.append(new EventDraft(CREATED_EVENT, 1, "Product", product.id(),
                product.rowVersion(), product.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
    }

    private void appendUpdated(Product before, Product after, CurrentActor actor) {
        Map<String, Object> payload = safePayload(after);
        payload.put("changedFields", changedFields(before, after));
        eventPort.append(new EventDraft(UPDATED_EVENT, 1, "Product", after.id(),
                after.rowVersion(), after.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
    }

    private Map<String, Object> safePayload(Product product) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", product.id());
        payload.put("code", product.code());
        payload.put("name", product.name());
        payload.put("status", product.status().name());
        payload.put("ownerUserId", product.ownerUserId());
        return payload;
    }

    private static List<String> changedFields(Product before, Product after) {
        java.util.ArrayList<String> fields = new java.util.ArrayList<>();
        if (!before.name().equals(after.name())) {
            fields.add("name");
        }
        if (!Objects.equals(before.description(), after.description())) {
            fields.add("description");
        }
        return List.copyOf(fields);
    }

    private StoredCommandResult stored(int status, Product product, CurrentActor actor) {
        try {
            return new StoredCommandResult(status,
                    objectMapper.writeValueAsString(view(product, actor, displayName(product))),
                    product.id(), StrongEtag.format(product.rowVersion()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("product response serialization failed", exception);
        }
    }

    private ProductView view(Product product, CurrentActor actor, String ownerDisplayName) {
        return ProductView.from(product, actor, ownerDisplayName);
    }

    private String displayName(Product product) {
        return minimalUsers.findByUserId(product.companyId(), product.ownerUserId())
                .map(MinimalUserSnapshot::displayName).orElse("-");
    }

    private static String displayName(Map<UUID, MinimalUserSnapshot> owners, UUID ownerUserId) {
        MinimalUserSnapshot owner = owners.get(ownerUserId);
        return owner == null ? "-" : owner.displayName();
    }

    private Product requiredVisible(CurrentActor actor, UUID productId) {
        requireActiveActor(actor);
        return repository.findVisibleById(actor, productId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private Product required(UUID companyId, UUID productId) {
        return repository.findById(companyId, productId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private Product requiredLocked(UUID companyId, UUID productId) {
        return repository.lockById(companyId, productId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private RuntimeException conditionalFailure(
            UUID companyId,
            UUID productId,
            long expectedVersion,
            ProductStatus expectedStatus
    ) {
        Product current = repository.findById(companyId, productId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (current.rowVersion() != expectedVersion) {
            return new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        if (current.status() != expectedStatus) {
            return new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        return new IllegalStateException("product conditional update failed without changed condition");
    }

    private static ProductApplicationSnapshot snapshot(Product product) {
        return ProductApplicationSnapshot.from(product);
    }

    private static void requireVersion(Product product, long expectedVersion) {
        if (product.rowVersion() != expectedVersion) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
    }

    private static void requireStatus(Product product, ProductStatus expected) {
        if (product.status() != expected) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
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
}
