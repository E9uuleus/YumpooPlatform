package com.yumpoo.platform.identityaccess.application.administration;

import java.util.List;

public record IdentityMemberPage(List<IdentityMemberView> items, long total) {
    public IdentityMemberPage {
        items = List.copyOf(items);
    }
}
