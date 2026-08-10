package com.yumpoo.platform.filestorage.api;

/**
 * 附件对外稳定状态。接收、排队和扫描只属于 UPLOADING 的内部处理阶段。
 */
public enum AttachmentStatus {
    UPLOADING,
    AVAILABLE,
    REJECTED,
    DELETED
}
