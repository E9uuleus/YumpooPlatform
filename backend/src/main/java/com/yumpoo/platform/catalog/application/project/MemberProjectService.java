package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class MemberProjectService {
    private final MemberProjectRepository repository;
    public MemberProjectService(MemberProjectRepository repository) { this.repository = repository; }

    public List<MemberProjectRepository.Project> find(CurrentActor actor, Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : repository.find(actor, ids);
    }

    public Page search(CurrentActor actor, String query, boolean archived, int offset, int limit) {
        if (offset < 0 || offset > 100000 || limit < 1 || limit > 100 || (query != null && query.length() > 200))
            throw ApplicationException.validation(new FieldViolation("query", "INVALID_VALUE", "项目查询范围无效"));
        String normalized = query == null ? "" : query.strip();
        return new Page(repository.search(actor, normalized, archived, offset, limit),
                repository.count(actor, normalized, archived));
    }
    public record Page(List<MemberProjectRepository.Project> items, long totalElements) {}
}
