package com.yumpoo.platform.identityaccess.application.administration;

import java.util.List;

public record DirectorySyncFailurePage(List<DirectorySyncFailureView> items, long total) {
    public DirectorySyncFailurePage {
        items = List.copyOf(items);
    }
}
