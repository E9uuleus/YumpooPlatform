package com.yumpoo.platform.identityaccess.api;

import java.util.List;

public record MinimalUserPage(List<MinimalUserSnapshot> items, long totalElements) {
    public MinimalUserPage {
        items = List.copyOf(items);
        if (totalElements < items.size()) {
            throw new IllegalArgumentException("totalElements must cover items");
        }
    }
}
