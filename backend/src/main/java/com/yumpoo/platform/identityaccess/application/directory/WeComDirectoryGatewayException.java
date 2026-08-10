package com.yumpoo.platform.identityaccess.application.directory;

import java.util.Objects;

/** 企微通讯录边界的安全失败；异常链中不得携带请求 URI 或响应正文。 */
public final class WeComDirectoryGatewayException extends RuntimeException {

    private static final String SAFE_MESSAGE = "WeCom directory service is unavailable";

    private final DirectorySnapshotFailure failure;

    public WeComDirectoryGatewayException(DirectorySnapshotFailure failure) {
        super(SAFE_MESSAGE);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public DirectorySnapshotFailure failure() {
        return failure;
    }
}
