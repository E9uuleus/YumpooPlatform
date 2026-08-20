package com.yumpoo.platform.catalog.api;

import java.util.List;

public record ProjectMemberCandidatePage(
        List<ProjectMemberCandidate> items, int page, int size, long totalElements, int totalPages
) { public ProjectMemberCandidatePage { items = List.copyOf(items); } }
