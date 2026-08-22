package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.domain.Content;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.yumpoo.platform.workitem.application.ContentModels.ContentLocator;

public interface ContentRepository {
    int insertAll(List<Content> contents);
    boolean insert(Content content);
    List<Content> findAll(UUID companyId, UUID projectId);
    Optional<ContentLocator> findLocator(UUID companyId, UUID contentId);
    Optional<Content> find(UUID companyId, UUID projectId, UUID contentId);
    Optional<Content> lock(UUID companyId, UUID projectId, UUID contentId);
    Optional<Content> lockForShare(UUID companyId, UUID projectId, UUID contentId);
    Optional<Content> update(Content content, long expectedVersion);
    boolean hasActiveForTemplate(UUID companyId, UUID projectId, String templateKey,
                                 int templateVersion);
}
