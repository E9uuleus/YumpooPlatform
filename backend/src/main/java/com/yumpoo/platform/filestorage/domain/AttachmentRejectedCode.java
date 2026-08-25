package com.yumpoo.platform.filestorage.domain;

/**
 * 附件处理的稳定失败分类。具体扫描器输出和本机路径不得进入该类型。
 */
public enum AttachmentRejectedCode {
    FILE_TOO_LARGE,
    FILE_TYPE_NOT_ALLOWED,
    MALWARE_DETECTED,
    SCAN_UNAVAILABLE,
    UPLOAD_INCOMPLETE,
    INTEGRITY_CHECK_FAILED,
    PARENT_NOT_WRITABLE,
    QUOTA_EXCEEDED
}
