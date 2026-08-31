package com.yumpoo.platform.catalog.api;

public interface ProductCommandPort {
    ProductSnapshot lockForArchive(ProductLifecycleMutation mutation);
    ProductSnapshot lockForRestore(ProductLifecycleMutation mutation);
    ProductMutationResult archive(ProductLifecycleMutation mutation);
    ProductMutationResult restore(ProductLifecycleMutation mutation);
    ProductMutationResult reassignOwner(ProductOwnerReassignmentMutation mutation);
}
