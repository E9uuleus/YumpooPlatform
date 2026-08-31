package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductArchiveBlockerCollectorTest {

    @Test
    void productionCoverageDeclaresOnlyRealProjectRelationSource() {
        assertThat(new ProductArchiveBlockerCollector(List.of(
                provider(ProductArchiveBlockerSource.PROJECT_RELATION, 0, true)))
                .collect(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void reportsPositiveProjectCountWithoutPretendingFeedbackExists() {
        ProductArchiveBlockerCollector collector = new ProductArchiveBlockerCollector(List.of(
                provider(ProductArchiveBlockerSource.PROJECT_RELATION, 3, true)));

        assertThat(collector.collect(UUID.randomUUID(), UUID.randomUUID()))
                .extracting("code", "count")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "ACTIVE_DEVELOPMENT_SUPPORT_PROJECTS", 3L));
    }

    @Test
    void missingDuplicateOrIncompleteProviderFailsClosed() {
        ProductArchiveBlockerProvider provider = provider(
                ProductArchiveBlockerSource.PROJECT_RELATION, 0, true);
        assertThatThrownBy(() -> new ProductArchiveBlockerCollector(List.of(),
                Set.of(ProductArchiveBlockerSource.PROJECT_RELATION)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductArchiveBlockerCollector(List.of(provider, provider),
                Set.of(ProductArchiveBlockerSource.PROJECT_RELATION)))
                .isInstanceOf(IllegalStateException.class);

        ProductArchiveBlockerCollector collector = new ProductArchiveBlockerCollector(List.of(
                provider(ProductArchiveBlockerSource.PROJECT_RELATION, 0, false)));
        assertThatThrownBy(() -> collector.collect(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApplicationException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.reason()).isEqualTo("ARCHIVE_BLOCKER_DEPENDENCY_UNAVAILABLE");
                });
    }

    private static ProductArchiveBlockerProvider provider(ProductArchiveBlockerSource source,
            long count, boolean complete) {
        return new ProductArchiveBlockerProvider() {
            @Override public ProductArchiveBlockerSource source() { return source; }
            @Override public ProductArchiveBlockerReport report(UUID companyId, UUID productId) {
                return new ProductArchiveBlockerReport(source, count, complete);
            }
        };
    }
}
