package com.yumpoo.platform.foundation.infrastructure.deployment;

/** 不含配置值的稳定部署配置错误。 */
public final class DeploymentValidationException extends IllegalStateException {

    private final String code;
    private final String propertyName;

    DeploymentValidationException(String code, String propertyName) {
        super(code + ":" + propertyName);
        this.code = code;
        this.propertyName = propertyName;
    }

    public String code() {
        return code;
    }

    public String propertyName() {
        return propertyName;
    }
}
