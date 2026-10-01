package com.yumpoo.platform.administration.application;

public enum ProjectArchiveBlockerSource {
    WORKITEM("OPEN_WORK_ITEMS"),
    WORKLOG("PENDING_WORKLOG_APPROVALS");

    private final String code;

    ProjectArchiveBlockerSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
