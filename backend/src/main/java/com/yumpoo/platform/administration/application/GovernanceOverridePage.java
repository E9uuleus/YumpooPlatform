package com.yumpoo.platform.administration.application;

import java.util.List;

public record GovernanceOverridePage(List<GovernanceOverrideRecord> items, int offset,
        int size, long totalElements) {
}
