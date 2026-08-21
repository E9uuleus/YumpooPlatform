package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.domain.Content;

import java.util.List;
import java.util.UUID;

public interface ContentRepository {
    int insertAll(List<Content> contents);
    boolean hasActiveForTemplate(UUID companyId, UUID projectId, String templateKey,
                                 int templateVersion);
}
