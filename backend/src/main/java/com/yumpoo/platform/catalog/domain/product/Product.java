package com.yumpoo.platform.catalog.domain.product;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record Product(
        UUID id,
        UUID companyId,
        String code,
        String name,
        String description,
        ProductStatus status,
        UUID ownerUserId,
        long rowVersion,
        Instant createdAt,
        UUID createdByUserId,
        Instant updatedAt,
        UUID updatedByUserId,
        Instant archivedAt,
        UUID archivedByUserId
) {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    public Product {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(createdByUserId, "createdByUserId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(updatedByUserId, "updatedByUserId must not be null");
        code = requireCode(code);
        name = normalizeRequired(name, 80, "name");
        description = normalizeOptional(description, 500, "description");
        if (rowVersion < 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("invalid version or timestamps");
        }
        boolean archivedFactsPresent = archivedAt != null && archivedByUserId != null;
        if ((status == ProductStatus.ARCHIVED) != archivedFactsPresent) {
            throw new IllegalArgumentException("archive facts must match product status");
        }
        if (archivedAt != null && (archivedAt.isBefore(createdAt) || archivedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("archivedAt must be within product lifetime");
        }
    }

    public static Product create(
            UUID id,
            UUID companyId,
            String code,
            String name,
            String description,
            UUID ownerUserId,
            UUID actorUserId,
            Instant now
    ) {
        return new Product(id, companyId, code, name, description, ProductStatus.ACTIVE,
                ownerUserId, 0, now, actorUserId, now, actorUserId, null, null);
    }

    public Product updateDetails(String newName, String newDescription, UUID actorUserId, Instant now) {
        requireActive();
        return new Product(id, companyId, code, newName, newDescription, status, ownerUserId,
                rowVersion + 1, createdAt, createdByUserId, now, actorUserId, null, null);
    }

    public Product archive(UUID actorUserId, Instant now) {
        requireActive();
        return new Product(id, companyId, code, name, description, ProductStatus.ARCHIVED,
                ownerUserId, rowVersion + 1, createdAt, createdByUserId,
                now, actorUserId, now, actorUserId);
    }

    public Product restore(UUID actorUserId, Instant now) {
        if (status != ProductStatus.ARCHIVED) {
            throw new IllegalStateException("product must be archived");
        }
        return new Product(id, companyId, code, name, description, ProductStatus.ACTIVE,
                ownerUserId, rowVersion + 1, createdAt, createdByUserId,
                now, actorUserId, null, null);
    }

    public Product reassignOwner(UUID newOwnerUserId, UUID actorUserId, Instant now) {
        Objects.requireNonNull(newOwnerUserId, "newOwnerUserId must not be null");
        if (ownerUserId.equals(newOwnerUserId)) {
            throw new IllegalStateException("new owner must differ from current owner");
        }
        return new Product(id, companyId, code, name, description, status,
                newOwnerUserId, rowVersion + 1, createdAt, createdByUserId,
                now, actorUserId, archivedAt, archivedByUserId);
    }

    public boolean hasSameDetails(String candidateName, String candidateDescription) {
        return name.equals(normalizeRequired(candidateName, 80, "name"))
                && Objects.equals(description, normalizeOptional(candidateDescription, 500, "description"));
    }

    private void requireActive() {
        if (status != ProductStatus.ACTIVE) {
            throw new IllegalStateException("product must be active");
        }
    }

    private static String requireCode(String value) {
        Objects.requireNonNull(value, "code must not be null");
        if (!CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("code must be a stable uppercase identifier");
        }
        return value;
    }

    private static String normalizeRequired(String value, int maximum, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maximum, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return normalized;
    }
}
