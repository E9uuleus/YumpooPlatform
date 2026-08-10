package com.yumpoo.platform.foundation.application.error;

/**
 * 跨模块稳定错误码。异常消息只允许保存可安全返回给调用方的内容。
 */
public enum StandardErrorCode {

    MALFORMED_REQUEST("请求格式不正确", false),
    AUTHENTICATION_REQUIRED("登录状态无效，请重新登录", false),
    ACCOUNT_DISABLED("账号已停用，请联系管理员", false),
    ACCESS_DENIED("无权执行此操作", false),
    RESOURCE_NOT_FOUND("资源不存在或不可访问", false),
    IDEMPOTENCY_KEY_REUSED("幂等键已用于不同的请求内容", false),
    REQUEST_IN_PROGRESS("请求正在处理中，请稍后重试", true),
    INVALID_STATE_TRANSITION("当前状态不允许执行此操作", false),
    WORKLOG_LOCKED("工时已提交或批准，不能普通修改", false),
    VERSION_CONFLICT("数据已被其他设备修改", false),
    FILE_TOO_LARGE("文件超过允许大小", false),
    FILE_TYPE_NOT_ALLOWED("文件类型不允许", false),
    VALIDATION_FAILED("请求字段校验失败", false),
    CLIENT_UPGRADE_REQUIRED("客户端版本过低，请升级后重试", false),
    PRECONDITION_REQUIRED("缺少必要的 If-Match 请求头", false),
    RATE_LIMITED("请求过于频繁", true),
    INTERNAL_ERROR("系统暂时无法处理请求", false),
    DEPENDENCY_UNAVAILABLE("依赖服务暂时不可用", true);

    private final String defaultMessage;
    private final boolean retryable;

    StandardErrorCode(String defaultMessage, boolean retryable) {
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public boolean retryable() {
        return retryable;
    }
}
