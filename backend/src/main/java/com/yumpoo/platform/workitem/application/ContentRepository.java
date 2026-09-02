package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.ContentModels.ContentLocator;
import com.yumpoo.platform.workitem.domain.Content;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentRepository {
    int insertAll(List<Content> contents);
    boolean insert(Content content);
    void initializeCatalog(UUID companyId, UUID projectId, Instant now);
    long catalogVersion(UUID companyId, UUID projectId);
    long lockCatalogVersion(UUID companyId, UUID projectId);
    boolean bumpCatalogVersion(UUID companyId, UUID projectId, long expectedVersion, Instant now);
    int nextSortOrder(UUID companyId, UUID projectId);
    long countActive(UUID companyId, UUID projectId, UUID excludingContentId);
    List<Content> findAll(UUID companyId, UUID projectId);
    Optional<ContentLocator> findLocator(UUID companyId, UUID contentId);
    Optional<Content> find(UUID companyId, UUID projectId, UUID contentId);
    Optional<Content> lock(UUID companyId, UUID projectId, UUID contentId);
    Optional<Content> lockForShare(UUID companyId, UUID projectId, UUID contentId);
    boolean hasActiveForTemplate(UUID companyId, UUID projectId, String templateKey,
                                 int templateVersion);
    Optional<Content> update(Content content, long expectedVersion);
    void replaceOrder(List<Content> contents);
}
