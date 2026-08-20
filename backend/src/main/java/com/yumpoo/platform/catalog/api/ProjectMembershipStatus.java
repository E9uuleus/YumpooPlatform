package com.yumpoo.platform.catalog.api;

public enum ProjectMembershipStatus {
    ACTIVE, REMOVED, ALL;

    public boolean includes(String value) {
        return this == ALL || name().equals(value);
    }
}
