package com.yumpoo.platform.catalog.api;

public interface ProductCommandPort {
    ProductMutationResult archive(ProductLifecycleMutation mutation);
    ProductMutationResult restore(ProductLifecycleMutation mutation);
    ProductMutationResult reassignOwner(ProductOwnerReassignmentMutation mutation);
}
