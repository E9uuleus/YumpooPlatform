package com.yumpoo.platform.foundation.application.concurrency;

/**
 * 条件更新影响零行后，在相同授权范围内复查得到的稳定失败原因。
 */
public enum ConditionalUpdateFailure {
    RESOURCE_NOT_VISIBLE,
    VERSION_CONFLICT,
    INVALID_STATE
}
