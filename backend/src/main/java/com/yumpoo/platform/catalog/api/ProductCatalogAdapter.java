package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.product.ProductApplicationSnapshot;
import com.yumpoo.platform.catalog.application.product.ProductChangeResult;
import com.yumpoo.platform.catalog.application.product.ProductLifecycleChange;
import com.yumpoo.platform.catalog.application.product.ProductOwnerChange;
import com.yumpoo.platform.catalog.application.product.ProductService;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ProductCatalogAdapter implements ProductSnapshotQuery, ProductOwnerScopeQuery, ProductCommandPort {

    private final ProductService service;

    public ProductCatalogAdapter(ProductService service) {
        this.service = service;
    }

    @Override
    public Optional<ProductSnapshot> findVisible(CurrentActor actor, UUID productId) {
        try {
            return Optional.of(snapshot(service.findVisibleSnapshot(actor, productId)));
        } catch (com.yumpoo.platform.foundation.application.error.ApplicationException exception) {
            if (exception.errorCode()
                    == com.yumpoo.platform.foundation.application.error.StandardErrorCode.RESOURCE_NOT_FOUND) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    @Override
    public Optional<ProductSnapshot> find(UUID companyId, UUID productId) {
        return service.findSnapshot(companyId, productId).map(ProductCatalogAdapter::snapshot);
    }

    @Override
    public List<ProductSnapshot> findActiveByOwner(UUID companyId, UUID ownerUserId) {
        return service.findActiveByOwner(companyId, ownerUserId).stream()
                .map(ProductCatalogAdapter::snapshot)
                .toList();
    }

    @Override
    public ProductMutationResult archive(ProductLifecycleMutation mutation) {
        return result(service.archive(new ProductLifecycleChange(
                mutation.companyId(), mutation.productId(), mutation.expectedRowVersion(),
                mutation.actorUserId())));
    }

    @Override
    public ProductMutationResult restore(ProductLifecycleMutation mutation) {
        return result(service.restore(new ProductLifecycleChange(
                mutation.companyId(), mutation.productId(), mutation.expectedRowVersion(),
                mutation.actorUserId())));
    }

    @Override
    public ProductMutationResult reassignOwner(ProductOwnerReassignmentMutation mutation) {
        return result(service.reassignOwner(new ProductOwnerChange(
                mutation.companyId(), mutation.productId(), mutation.expectedRowVersion(),
                mutation.newOwnerUserId(), mutation.actorUserId())));
    }

    private static ProductMutationResult result(ProductChangeResult result) {
        return new ProductMutationResult(snapshot(result.before()), snapshot(result.after()));
    }

    private static ProductSnapshot snapshot(ProductApplicationSnapshot product) {
        return new ProductSnapshot(product.productId(), product.companyId(), product.code(),
                product.name(), product.description(), ProductLifecycleStatus.valueOf(product.status()),
                product.ownerUserId(), product.rowVersion());
    }
}
