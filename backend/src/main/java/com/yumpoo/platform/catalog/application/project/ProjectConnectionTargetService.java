package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.application.project.ProjectConnectionTargetRepository.Target;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectConnectionTargetService {
    public record Page(List<Target> items, int page, int size, long totalElements, int totalPages) {}

    private final ProjectConnectionTargetRepository targets;
    private final ProjectLifecycleService lifecycle;

    public ProjectConnectionTargetService(ProjectConnectionTargetRepository targets,
            ProjectLifecycleService lifecycle) {
        this.targets = targets;
        this.lifecycle = lifecycle;
    }

    @Transactional(readOnly = true)
    public Page searchActive(UUID companyId, String query, OffsetPageRequest page) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.length() > 80)
            throw ApplicationException.validation(new FieldViolation(
                    "query", "INVALID_LENGTH", "项目搜索词不能超过 80 个字符"));
        if (page.size() > 50)
            throw ApplicationException.validation(new FieldViolation(
                    "size", "PAGE_SIZE_OUT_OF_RANGE", "分页大小必须在 1 到 50 之间"));
        long total = targets.countActive(companyId, normalized);
        return new Page(targets.searchActive(companyId, normalized, page), page.page(), page.size(),
                total, (int) Math.ceil((double) total / page.size()));
    }

    @Transactional(readOnly = true)
    public List<Target> findByIds(UUID companyId, Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : targets.findByIds(companyId, ids);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Target lockAsConnectionTarget(UUID companyId, UUID projectId) {
        ProjectApplicationSnapshot project = lifecycle.lockForNewFact(companyId, projectId);
        return new Target(project.projectId(), project.code(), project.name(), project.lifecycle());
    }
}
