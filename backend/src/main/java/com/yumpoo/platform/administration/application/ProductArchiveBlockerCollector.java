package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.SafeBlocker;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public final class ProductArchiveBlockerCollector {

    private static final Set<ProductArchiveBlockerSource> DECLARED_SOURCES =
            Set.of(ProductArchiveBlockerSource.PROJECT_RELATION);
    private final Map<ProductArchiveBlockerSource, ProductArchiveBlockerProvider> providers;
    private final Set<ProductArchiveBlockerSource> declaredSources;

    @Autowired
    public ProductArchiveBlockerCollector(List<ProductArchiveBlockerProvider> providers) {
        this(providers, DECLARED_SOURCES);
    }

    ProductArchiveBlockerCollector(List<ProductArchiveBlockerProvider> providers,
            Set<ProductArchiveBlockerSource> declaredSources) {
        Map<ProductArchiveBlockerSource, ProductArchiveBlockerProvider> indexed =
                new EnumMap<>(ProductArchiveBlockerSource.class);
        for (ProductArchiveBlockerProvider provider : providers) {
            if (indexed.put(provider.source(), provider) != null) {
                throw new IllegalStateException("duplicate product archive blocker provider");
            }
        }
        if (!indexed.keySet().equals(declaredSources)) {
            throw new IllegalStateException("product archive blocker provider coverage mismatch");
        }
        this.providers = Map.copyOf(indexed);
        this.declaredSources = Set.copyOf(declaredSources);
    }

    public List<SafeBlocker> collect(UUID companyId, UUID productId) {
        return declaredSources.stream().sorted().map(source -> report(source, companyId, productId))
                .filter(blocker -> blocker.count() > 0).toList();
    }

    private SafeBlocker report(ProductArchiveBlockerSource source, UUID companyId, UUID productId) {
        try {
            ProductArchiveBlockerReport report = providers.get(source).report(companyId, productId);
            if (report == null || report.source() != source || !report.complete()) {
                throw unavailable();
            }
            return new SafeBlocker(source.code(), report.count());
        } catch (ApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static ApplicationException unavailable() {
        return ApplicationException.withReason(StandardErrorCode.DEPENDENCY_UNAVAILABLE,
                "ARCHIVE_BLOCKER_DEPENDENCY_UNAVAILABLE");
    }
}
