package com.yumpoo.platform.catalog.domain.product;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    private static final UUID PRODUCT_ID = UUID.fromString("23000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("23000000-0000-4000-8000-000000000002");
    private static final UUID OWNER_ID = UUID.fromString("23000000-0000-4000-8000-000000000003");
    private static final UUID ACTOR_ID = UUID.fromString("23000000-0000-4000-8000-000000000004");
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void createsActiveProductAndNormalizesMutableText() {
        Product product = Product.create(PRODUCT_ID, COMPANY_ID, "YUMPOO", "  Yumpoo  ",
                "   ", OWNER_ID, ACTOR_ID, NOW);

        assertThat(product.code()).isEqualTo("YUMPOO");
        assertThat(product.name()).isEqualTo("Yumpoo");
        assertThat(product.description()).isNull();
        assertThat(product.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product.rowVersion()).isZero();
    }

    @Test
    void rejectsUnstableCodeAndOversizedDescription() {
        assertThatThrownBy(() -> Product.create(PRODUCT_ID, COMPANY_ID, "bad-code", "Yumpoo",
                null, OWNER_ID, ACTOR_ID, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Product.create(PRODUCT_ID, COMPANY_ID, "YUMPOO", "Yumpoo",
                "x".repeat(501), OWNER_ID, ACTOR_ID, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void archivedProductIsReadOnlyButOwnerCanBeReassignedBeforeRestore() {
        Product product = Product.create(PRODUCT_ID, COMPANY_ID, "YUMPOO", "Yumpoo",
                null, OWNER_ID, ACTOR_ID, NOW);
        Product archived = product.archive(ACTOR_ID, NOW.plusSeconds(1));

        assertThatThrownBy(() -> archived.updateDetails("Changed", null, ACTOR_ID, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);

        UUID replacement = UUID.fromString("23000000-0000-4000-8000-000000000005");
        Product reassigned = archived.reassignOwner(replacement, ACTOR_ID, NOW.plusSeconds(2));
        Product restored = reassigned.restore(ACTOR_ID, NOW.plusSeconds(3));
        assertThat(restored.ownerUserId()).isEqualTo(replacement);
        assertThat(restored.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(restored.archivedAt()).isNull();
        assertThat(restored.rowVersion()).isEqualTo(3);
    }

    @Test
    void noChangeUsesNormalizedFullMutableSnapshot() {
        Product product = Product.create(PRODUCT_ID, COMPANY_ID, "YUMPOO", "Yumpoo",
                null, OWNER_ID, ACTOR_ID, NOW);

        assertThat(product.hasSameDetails("  Yumpoo ", "  ")).isTrue();
    }
}
