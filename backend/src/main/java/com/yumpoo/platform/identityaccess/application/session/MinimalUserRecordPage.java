package com.yumpoo.platform.identityaccess.application.session;

import java.util.List;

public record MinimalUserRecordPage(List<MinimalUserRecord> items, long totalElements) {
    public MinimalUserRecordPage { items=List.copyOf(items); }
}
