package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.domain.Content;

import java.util.List;

public interface ContentRepository {
    int insertAll(List<Content> contents);
}
