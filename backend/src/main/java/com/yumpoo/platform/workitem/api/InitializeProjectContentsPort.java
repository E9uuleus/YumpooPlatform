package com.yumpoo.platform.workitem.api;

import java.util.List;

public interface InitializeProjectContentsPort {
    List<InitializedProjectContent> initialize(ProjectContentInitialization initialization);
}
