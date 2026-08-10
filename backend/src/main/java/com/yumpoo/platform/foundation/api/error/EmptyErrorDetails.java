package com.yumpoo.platform.foundation.api.error;

/**
 * M0-09 错误详情固定为空对象；后续详情必须先定义收窄后的契约类型。
 */
public record EmptyErrorDetails() {

    public static final EmptyErrorDetails INSTANCE = new EmptyErrorDetails();
}
