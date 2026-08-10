package com.yumpoo.platform.filestorage.application;

import java.io.IOException;

/** 字节流未完整接收；消息故意不包含底层路径或请求内容。 */
public final class UploadIncompleteException extends IOException {

    public UploadIncompleteException() {
        super("attachment upload did not complete");
    }
}
