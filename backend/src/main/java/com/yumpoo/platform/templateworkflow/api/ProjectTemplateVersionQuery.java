package com.yumpoo.platform.templateworkflow.api;

import java.util.Optional;

public interface ProjectTemplateVersionQuery {

    Optional<ProjectTemplateSnapshot> findAny(String templateKey, int version);
}
