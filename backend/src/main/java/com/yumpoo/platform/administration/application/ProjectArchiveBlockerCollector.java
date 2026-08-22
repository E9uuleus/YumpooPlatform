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
public final class ProjectArchiveBlockerCollector {

    private static final Set<ProjectArchiveBlockerSource> DECLARED_SOURCES =
            Set.of(ProjectArchiveBlockerSource.WORKITEM);
    private final Map<ProjectArchiveBlockerSource, ProjectArchiveBlockerProvider> providers;
    private final Set<ProjectArchiveBlockerSource> declaredSources;

    @Autowired
    public ProjectArchiveBlockerCollector(List<ProjectArchiveBlockerProvider> providers) {
        this(providers, DECLARED_SOURCES);
    }

    ProjectArchiveBlockerCollector(List<ProjectArchiveBlockerProvider> providers,
            Set<ProjectArchiveBlockerSource> declaredSources) {
        Map<ProjectArchiveBlockerSource, ProjectArchiveBlockerProvider> indexed =
                new EnumMap<>(ProjectArchiveBlockerSource.class);
        for (ProjectArchiveBlockerProvider provider : providers) {
            if (indexed.put(provider.source(), provider) != null) {
                throw new IllegalStateException("duplicate project archive blocker provider");
            }
        }
        if (!indexed.keySet().equals(declaredSources)) {
            throw new IllegalStateException("project archive blocker provider coverage mismatch");
        }
        this.providers = Map.copyOf(indexed);
        this.declaredSources = Set.copyOf(declaredSources);
    }

    public List<SafeBlocker> collect(UUID companyId, UUID projectId) {
        return declaredSources.stream().sorted().map(source -> report(source, companyId, projectId))
                .filter(blocker -> blocker.count() > 0).toList();
    }

    private SafeBlocker report(ProjectArchiveBlockerSource source, UUID companyId, UUID projectId) {
        try {
            ProjectArchiveBlockerReport report = providers.get(source).report(companyId, projectId);
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
