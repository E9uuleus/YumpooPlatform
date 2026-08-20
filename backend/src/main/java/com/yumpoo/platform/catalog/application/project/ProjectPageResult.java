package com.yumpoo.platform.catalog.application.project;

import java.util.List;

public record ProjectPageResult(List<ProjectQueryRow> items, long totalElements) {
}
