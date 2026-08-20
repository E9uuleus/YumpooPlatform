package com.yumpoo.platform.templateworkflow.api;

import java.util.List;
import java.util.Optional;

public interface PublishedProjectTemplateQuery {

    List<ProjectTemplateSnapshot> findAllPublished();

    Optional<ProjectTemplateSnapshot> findPublished(String templateKey, int version);

    Optional<ProjectTemplateSnapshot> findPublishedForCreation(String templateKey, int version);
}
