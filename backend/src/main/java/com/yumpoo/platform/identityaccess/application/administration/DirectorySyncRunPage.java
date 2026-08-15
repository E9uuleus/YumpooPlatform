package com.yumpoo.platform.identityaccess.application.administration;

import java.util.List;

public record DirectorySyncRunPage(List<DirectorySyncRunView> items, long total) {
    public DirectorySyncRunPage {
        items = List.copyOf(items);
    }
}
